package ar.unrn.video.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Service responsible for retrieving and caching Keycloak JWT access tokens
 * for authenticating downstream calls to the VideoClub MCP server.
 */
@Service
public class KeycloakTokenService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenService.class);

    private final String tokenUrl;
    private final String clientId;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedToken;
    private Instant expiresAt = Instant.MIN;

    public KeycloakTokenService(
            @Value("${videoclub.keycloak.token-url}") final String tokenUrl,
            @Value("${videoclub.keycloak.client-id}") final String clientId,
            @Value("${videoclub.keycloak.username}") final String username,
            @Value("${videoclub.keycloak.password}") final String password) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns a valid JWT access token, renewing it if expired or nearing expiration.
     */
    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().plusSeconds(30).isBefore(expiresAt)) {
            return cachedToken;
        }

        try {
            log.info("Requesting new JWT from Keycloak at {}", tokenUrl);

            final Map<String, String> formData = Map.of(
                    "client_id", clientId,
                    "username", username,
                    "password", password,
                    "grant_type", "password",
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
                throw new IllegalStateException("Failed to obtain token from Keycloak. HTTP "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = objectMapper.readTree(response.body());
            this.cachedToken = root.path("access_token").asText();
            final long expiresIn = root.path("expires_in").asLong(1800);
            this.expiresAt = Instant.now().plusSeconds(expiresIn);

            log.info("Keycloak JWT obtained successfully (expires in {}s)", expiresIn);
            return this.cachedToken;

        } catch (Exception e) {
            log.error("Error obtaining JWT from Keycloak: {}", e.getMessage(), e);
            throw new RuntimeException("Could not authenticate with Keycloak: " + e.getMessage(), e);
        }
    }
}
