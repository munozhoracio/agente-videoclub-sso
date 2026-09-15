package ar.unrn.video.agent.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the identity rules of the Token Relay path.
 *
 * <p>These are security invariants rather than behaviour details: a regression here would not
 * break a feature, it would quietly let one caller's tool call run under another identity.
 */
class TokenRelayServiceTest {

    private static final String RAW_TOKEN = "header.payload.signature";

    private final TokenRelayService service = new TokenRelayService(
            "http://localhost:9090/realms/videoclub/protocol/openid-connect/token",
            "videoclub-backend",
            "secret");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(final String subject) {
        final Jwt jwt = Jwt.withTokenValue(RAW_TOKEN)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(), subject));
    }

    @Test
    @DisplayName("Relays the caller's own raw token when the SecurityContext holds a JWT")
    void relaysTheCallersToken() {
        authenticateAs("usuarioadmin");

        assertThat(service.getUserBearerToken()).isEqualTo(RAW_TOKEN);
    }

    @Test
    @DisplayName("Refuses to produce a token instead of falling back to the service account")
    void refusesWithoutACallerIdentity() {
        assertThatThrownBy(service::getUserBearerToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated JWT in the SecurityContext");
    }

    @Test
    @DisplayName("Captures the caller's token so it can be carried into the MCP transport context")
    void capturesTheCallersTokenForTheTransportContext() {
        authenticateAs("usuariocliente");

        assertThat(service.currentUserToken()).contains(RAW_TOKEN);
    }

    @Test
    @DisplayName("Captures nothing — without throwing — when no caller identity exists, which is how the startup handshake is recognised")
    void capturesNothingDuringTheStartupHandshake() {
        assertThat(service.currentUserToken()).isEmpty();
    }
}
