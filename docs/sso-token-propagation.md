# Architecture: Frontend Integration & SSO Token Propagation (Token Relay)

## 1. Executive Summary & Problem Statement

In the initial prototype of `videoclub-agent`, the agent authenticated against Keycloak using **Direct Grant (Resource Owner Password Credentials - ROPC)** with credentials hardcoded in `.env`:
```properties
KEYCLOAK_USERNAME=usuarioadmin
KEYCLOAK_PASSWORD=usuarioadmin
```

While acceptable for an initial offline spike, keeping user credentials in backend configuration violates core architectural principles:
1. **OAuth 2.1 Deprecation**: The ROPC grant is formally deprecated and removed in OAuth 2.1 due to credential leakage risks.
2. **Loss of Identity & Auditability**: Every MCP tool execution is logged under `usuarioadmin`, destroying non-repudiation in event sourcing and audit logs.
3. **Broken Authorization Boundaries**: A regular user in the React frontend could ask the agent for sensitive data (e.g., membership records), and the agent would fulfill it because it runs with full admin privileges.

---

## 2. Target Architecture: The Token Relay Pattern

When `react-sso` integrates with `videoclub-agent`, we transition to the **OAuth2 Token Relay Pattern**:

```
 ┌─────────────┐                      ┌────────────────────┐
 │  React SPA  │                      │    Keycloak SSO    │
 │ (react-sso) │                      │     (:9091)        │
 └──────┬──────┘                      └─────────┬──────────┘
        │                                       │
        │ 1. Auth Code Flow + PKCE              │
        ├──────────────────────────────────────>│
        │ 2. Return User Access Token (JWT)     │
        │<──────────────────────────────────────┤
        │                                       │
        │ 3. POST /api/agent/chat               │
        │    Authorization: Bearer <user_jwt>   │
        ▼                                       │
 ┌──────────────────────┐                       │
 │   videoclub-agent    │                       │
 │       (:8085)        │                       │
 ├──────────────────────┤                       │
 │ • Resource Server    │ 4. Validate JWT       │
 │ • Token Extractor    ├───────────────────────┤ (via JWKS cache)
 │ • Spring AI Agent    │                       │
 └──────┬───────────────┘                       │
        │                                       │
        │ 5. Streamable HTTP MCP Request        │
        │    Authorization: Bearer <user_jwt>   │
        ▼                                       │
 ┌──────────────────────┐                       │
 │    springboot-sso    │                       │
 │       (:8080)        │                       │
 ├──────────────────────┤                       │
 │ • MCP Server         │ 6. Evaluate Security Context
 │ • @PreAuthorize      │    (Roles & Permissions of User)
 └──────────────────────┘
```

---

## 3. End-to-End Workflow

1. **User Authentication**:
   The user logs into `react-sso` via Keycloak using standard Authorization Code Flow with PKCE.
2. **Context-Bound Request**:
   When the user types a prompt in the React AI chat widget, the frontend sends an HTTP POST request to `videoclub-agent`:
   ```http
   POST /api/agent/chat
   Host: localhost:8085 (or via Gateway :9500)
   Authorization: Bearer eyJhbGciOiJSUzI1Ni...
   Content-Type: application/json

   {
     "prompt": "¿Quiénes son los socios registrados?"
   }
   ```
3. **Resource Server Validation**:
   `videoclub-agent` acts as an **OAuth2 Resource Server**, validating the token signature against Keycloak's JWKS endpoint.
4. **Token Relay into MCP**:
   During the Streamable HTTP MCP handshake and tool calls to `springboot-sso` (`http://localhost:8080/mcp`), the agent passes the **exact same Bearer token** received from the user.
5. **Enforcing Least Privilege**:
   * If an **Admin** asks for movies and socios, `springboot-sso` allows both MCP tools.
   * If a **Regular User** asks for socio records, the `@PreAuthorize` on the tool throws
     `AccessDeniedException`.
   * The agent gracefully explains to the user: *"You do not have permission to view member records."*

> **How the denial actually travels — it is not an HTTP 403.** `AccessDeniedException` is a
> `RuntimeException`, and Spring AI's `SyncStatelessMcpToolMethodCallback` catches `RuntimeException`
> and converts it into `createSyncErrorResult(e)` — a `CallToolResult` flagged `isError`, carried
> inside a normal **HTTP 200** JSON-RPC response. The MCP endpoint itself already authenticated the
> request, so the transport call succeeds; only the *tool* fails.
>
> This is what makes the graceful explanation in the last bullet possible: the denial reaches the LLM
> as tool output it can read and paraphrase. A real 403 would abort the call before the model ever
> saw it. Do not write client code that switches on a `403` status here — there is none.

---

## 4. Required Changes in `videoclub-agent`

### A. Deprecate Credentials in `.env`
Remove:
```diff
- KEYCLOAK_USERNAME=usuarioadmin
- KEYCLOAK_PASSWORD=usuarioadmin
```

Remove the same two keys from **`.env.example`**, which is the tracked file and currently ships the
real development credentials as its placeholder values. Drop the matching
`videoclub.keycloak.username` / `videoclub.keycloak.password` defaults from `application.yml` as
well, otherwise the values survive in the image even with an empty `.env`.

### B. Add Spring Security Resource Server Dependency
In `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

### C. Configure Resource Server in `application.yml`
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9091/realms/videoclub
          jwk-set-uri: http://localhost:9091/realms/videoclub/protocol/openid-connect/certs
```

`springboot-sso` declares both properties, and this service should match. With `issuer-uri` alone,
startup performs OIDC discovery and therefore **fails if Keycloak is not already running**; the
explicit `jwk-set-uri` removes that startup coupling. Note also that the `iss` claim in the incoming
token must match `issuer-uri` exactly — a token minted through a different hostname (for example
`host.docker.internal`) will be rejected.

### D. Token Propagation Mechanism
Replace `KeycloakTokenService` with a request-scoped token extractor:
```java
@Component
public class RequestTokenHolder {
    public String getBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }
}
```

The MCP Client customizer then dynamically grabs the token from the active HTTP request context rather than requesting a static token from Keycloak.

This extractor works: `AgentService.chat()` calls `chatClient.prompt()...call()` synchronously, so
tool execution happens on the servlet request thread and the thread-local `SecurityContextHolder` is
visible to the transport customizer.

#### ⚠️ Swapping the customizer alone is not enough

The current MCP client is wired entirely at **startup**, before any user request exists:

```java
// McpClientConfiguration — inside the @Bean factory method
client.initialize();                                  // handshake at boot

// AgentService — inside the CONSTRUCTOR
final ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
this.chatClient = chatClientBuilder
        .defaultTools((Object[]) toolCallbacks)       // frozen for the lifetime of the bean
        .build();
```

At boot there is no HTTP request, therefore no `SecurityContext`, therefore no bearer token. The MCP
endpoint in `springboot-sso` is not anonymous — its `SecurityConfiguration` ends in
`anyRequest().authenticated()` — so both `initialize()` and `tools/list` return **401**. The startup
handshake failure is already swallowed by a `log.warn`, so the service still boots, but
`getToolCallbacks()` yields nothing and the frozen `defaultTools(...)` array leaves the agent with
**zero tools for the entire life of the process**. The symptom is an agent that answers every
question from the model's own knowledge and never calls the VideoClub at all.

There is a real tension here the rest of this document must not gloss over: **tool discovery needs
some credential even after user credentials are removed.** Two viable resolutions:

| Option | How it works | Trade-off |
|---|---|---|
| **Per-request MCP client** | Build the transport, discover tools, and run the call inside the request scope, using the caller's token throughout. | Highest fidelity: discovery itself is authorized as the user. Costs one handshake per request unless pooled. |
| **Split discovery from execution** | Keep a narrow `client_credentials` service account (not ROPC, not a human user) solely for `initialize()`/`tools/list` at boot; relay the user's token on every `tools/call`. | Keeps startup cheap, but reintroduces a stored secret — a client secret rather than a user password, which is the acceptable half of the trade. |

Either way, `AgentService` must stop capturing `ToolCallback[]` in its constructor. Whichever option
is chosen has to be decided **before** implementation starts, because it determines the bean scopes
of `McpClientConfiguration`, `AgentService`, and the `ChatClient`.

### E. Gateway Routing & CORS
Route agent calls through the Spring Cloud Gateway (`:9500`), which `react-sso` already uses as its
base URL (`VITE_API_BASE_URL=http://localhost:9500`).

**CORS needs no new work.** The gateway config (`docker/gateway/gateway.yml` in the `springboot-sso`
repository) already declares a global policy covering every route:

```yaml
globalcors:
  cors-configurations:
    '[/**]':
      allowedOriginPatterns: "*"
      allowedMethods: "*"
      allowedHeaders: "*"
      allowCredentials: true
```

Adding a CORS filter on `:8085` is only necessary if the frontend is pointed straight at the agent,
bypassing the gateway — which this design does not do.
