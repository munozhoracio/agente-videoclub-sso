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
import java.util.stream.Collectors;

/**
 * Manages JWT tokens for downstream MCP calls.
 * 1. Propagates the caller's JWT if an active HTTP request context exists (Token Relay).
 * 2. Falls back to a service account token via client_credentials for startup tool discovery.
 */
@Service
public class TokenRelayService {

    private static final Logger log = LoggerFactory.getLogger(TokenRelayService.class);

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedServiceToken;
    private Instant serviceTokenExpiresAt = Instant.MIN;

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
     * Resolves the token to use for downstream MCP calls:
     * - Returns the active user's JWT from SecurityContextHolder if present.
     * - Falls back to the cached service account token.
     */
    public String getBearerToken() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            log.debug("Using user Bearer token from active SecurityContext: {}", jwtAuth.getName());
            return jwtAuth.getToken().getTokenValue();
        }

        log.debug("No user SecurityContext found. Falling back to service account client_credentials token");
        return getServiceAccountToken();
    }

    /**
     * Retrieves or refreshes a service account JWT using client_credentials grant.
     */
    public synchronized String getServiceAccountToken() {
        if (cachedServiceToken != null && Instant.now().plusSeconds(30).isBefore(serviceTokenExpiresAt)) {
            return cachedServiceToken;
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
            this.cachedServiceToken = root.path("access_token").asText();
            final long expiresIn = root.path("expires_in").asLong(1800);
            this.serviceTokenExpiresAt = Instant.now().plusSeconds(expiresIn);

            log.info("Service account JWT obtained successfully (expires in {}s)", expiresIn);
            return this.cachedServiceToken;

        } catch (Exception e) {
            log.error("Error obtaining service account JWT from Keycloak: {}", e.getMessage(), e);
            throw new RuntimeException("Could not authenticate service account with Keycloak: " + e.getMessage(), e);
        }
    }
}
