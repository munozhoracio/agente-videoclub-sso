package ar.unrn.video.agent.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collections;
import java.util.List;

/**
 * Value object representing the authenticated user's profile extracted from the Keycloak JWT.
 */
public record UserProfile(
        String id,
        String username,
        String fullName,
        String email,
        List<String> roles
) {
    public static UserProfile from(final Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            final Jwt jwt = jwtAuth.getToken();
            final String sub = jwt.getSubject();
            final String username = jwt.getClaimAsString("preferred_username") != null
                    ? jwt.getClaimAsString("preferred_username")
                    : (sub != null ? sub : "usuario");
            final String name = jwt.getClaimAsString("name") != null
                    ? jwt.getClaimAsString("name")
                    : username;
            final String email = jwt.getClaimAsString("email") != null
                    ? jwt.getClaimAsString("email")
                    : "";

            List<String> roles = jwt.getClaimAsStringList("groups");
            if (roles == null || roles.isEmpty()) {
                roles = jwtAuth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(a -> a.replace("ROLE_", "").toLowerCase())
                        .toList();
            }
            if (roles.isEmpty()) {
                roles = Collections.singletonList("cliente");
            }

            return new UserProfile(sub != null ? sub : username, username, name, email, roles);
        }
        return new UserProfile("anonimo", "anonimo", "Usuario Anónimo", "", List.of("cliente"));
    }
}
