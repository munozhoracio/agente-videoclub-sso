package ar.unrn.video.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves the bearer tokens used for outbound MCP calls to {@code springboot-sso}.
 *
 * <p>There are exactly two callers, and they are deliberately kept apart:
 *
 * <ul>
 *   <li>{@link #getUserBearerToken()} — the Token Relay path. Returns the JWT of the human who
 *       issued the current HTTP request, so every {@code tools/call} is authorized as that person
 *       and {@code @PreAuthorize} on the MCP tools evaluates their real roles.</li>
 *   <li>{@link #getDiscoveryToken()} — the bootstrap path. A {@code client_credentials} token for
 *       the {@code videoclub-backend} service account, used only for the MCP {@code initialize()}
 *       handshake at startup, when no HTTP request and therefore no user identity exists yet.</li>
 * </ul>
 *
 * <h2>Why there is no fallback between them</h2>
 *
 * <p>An earlier revision had {@code getBearerToken()} silently fall back to the service account
 * whenever the {@code SecurityContext} held no {@link JwtAuthenticationToken}. That made the agent's
 * identity depend on a condition no caller could see: any code path running off the servlet thread
 * would have executed tools as the service account instead of as the user, which is precisely the
 * shared-privileged-identity problem the Token Relay pattern exists to remove.
 *
 * <p>It happened to be harmless only because {@code service-account-videoclub-backend} holds no
 * {@code movie-permission-read} or {@code socio-permission-read} client role in the realm — an
 * accident of configuration, not a guarantee. {@link #getUserBearerToken()} now throws instead, so
 * a missing user identity fails the call rather than quietly downgrading it. Granting business
 * roles to that service account must stay unnecessary; see {@code docs/sso-token-propagation.md}.
 */
@Service
public class TokenRelayService {

    private static final Logger log = LoggerFactory.getLogger(TokenRelayService.class);

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedDiscoveryToken;
    private Instant discoveryTokenExpiresAt = Instant.MIN;

    public TokenRelayService(
            @Value("${videoclub.keycloak.token-url}") final String tokenUrl,
            @Value("${videoclub.keycloak.client-id}") final String clientId,
            @Value("${videoclub.keycloak.client-secret}") final String clientSecret) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns the caller's JWT so it can be relayed to the MCP server.
     *
     * @return the raw token value of the authenticated user
     * @throws IllegalStateException if the current {@code SecurityContext} holds no authenticated
     *                               JWT. Failing here is intentional: the alternative is executing
     *                               someone else's tool call under a different identity.
     */
    public String getUserBearerToken() {
        return currentJwtAuthentication()
                .map(jwtAuth -> {
                    log.debug("Relaying bearer token of authenticated caller: {}", jwtAuth.getName());
                    return jwtAuth.getToken().getTokenValue();
                })
                .orElseThrow(() -> new IllegalStateException(
                        "No authenticated JWT in the SecurityContext; refusing to call MCP tools "
                        + "without a caller identity. MCP tool calls must run under the user's own "
                        + "token."));
    }

    /**
     * Returns the caller's JWT if this thread has one, without failing when it does not.
     *
     * <p>This is the capture half of {@link #getUserBearerToken()}. {@code SecurityContextHolder}
     * is a {@code ThreadLocal}, and the MCP client writes its HTTP requests on its own worker
     * threads, so looking the identity up at the moment the request is built finds nothing. This
     * method is called instead on the thread that <em>starts</em> the operation — the servlet
     * thread, where the context is still visible — so the token can be carried into the reactive
     * pipeline rather than searched for at the far end of it.
     *
     * <p>An empty result is a normal outcome, not an error: it is how the startup handshake, which
     * runs before any HTTP request exists, is told apart from a user-initiated call.
     *
     * @return the caller's raw token value, or empty when this thread carries no authenticated JWT
     */
    public Optional<String> currentUserToken() {
        return currentJwtAuthentication().map(jwtAuth -> {
            log.debug("Capturing bearer token of caller {} for the MCP transport context",
                    jwtAuth.getName());
            return jwtAuth.getToken().getTokenValue();
        });
    }

    private static Optional<JwtAuthenticationToken> currentJwtAuthentication() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwtAuth ? Optional.of(jwtAuth) : Optional.empty();
    }

    /**
     * Retrieves (or refreshes) the service account JWT used for the startup MCP handshake.
     *
     * <p>This token only needs to satisfy {@code anyRequest().authenticated()} on {@code /mcp}:
     * {@code initialize()} and {@code tools/list} are protocol-level operations, while the
     * {@code @PreAuthorize} checks live on the tool methods reached by {@code tools/call}. The
     * service account therefore needs — and must keep — zero business permissions.
     */
    public synchronized String getDiscoveryToken() {
        if (cachedDiscoveryToken != null && Instant.now().plusSeconds(30).isBefore(discoveryTokenExpiresAt)) {
            return cachedDiscoveryToken;
        }

        try {
            log.info("Requesting new service account JWT from Keycloak at {} for client {}", tokenUrl, clientId);

            final Map<String, String> formData = Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "grant_type", "client_credentials",
                    "scope", "openid"
            );

            final String formBody = formData.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to obtain service account token. HTTP "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = objectMapper.readTree(response.body());
            this.cachedDiscoveryToken = root.path("access_token").asText();
            final long expiresIn = root.path("expires_in").asLong(1800);
            this.discoveryTokenExpiresAt = Instant.now().plusSeconds(expiresIn);

            log.info("Service account JWT obtained successfully (expires in {}s)", expiresIn);
            return this.cachedDiscoveryToken;

        } catch (Exception e) {
            log.error("Error obtaining service account JWT from Keycloak: {}", e.getMessage(), e);
            throw new IllegalStateException("Could not authenticate service account with Keycloak: " + e.getMessage(), e);
        }
    }
}
