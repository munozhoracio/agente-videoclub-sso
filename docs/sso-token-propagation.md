# SSO Token Propagation (Token Relay) — Implemented Architecture

**Status:** implemented and verified end-to-end.
**Scope:** `videoclub-agent` (`:8085`), `springboot-sso` (`:8080`), Keycloak realm `videoclub` (`:9091`).

This document describes how the agent currently authenticates its callers and how it authorizes its
MCP tool calls. It is a description of the running system, not a proposal.

---

## 1. What this replaced

The initial prototype authenticated against Keycloak with the **Resource Owner Password Credentials**
grant, using a human's credentials stored in `.env`:

```properties
KEYCLOAK_USERNAME=usuarioadmin
KEYCLOAK_PASSWORD=usuarioadmin
```

Three problems made that untenable beyond an offline spike:

1. **ROPC is deprecated.** The grant is removed in OAuth 2.1 because it requires the client to handle
   the user's password directly.
2. **Identity was lost.** Every MCP tool execution was attributed to `usuarioadmin`, so audit logs
   and event sourcing recorded the agent, never the person.
3. **Authorization was bypassed.** Any user of the React app could ask the agent for member records
   and get them, because the agent always ran with admin privileges.

All three are gone. `KEYCLOAK_USERNAME` and `KEYCLOAK_PASSWORD` no longer exist in `.env`,
`.env.example`, or `application.yml`.

---

## 2. Architecture

```
 ┌─────────────┐                      ┌────────────────────┐
 │  React SPA  │                      │    Keycloak SSO    │
 │ (react-sso) │                      │      (:9091)       │
 └──────┬──────┘                      └─────────┬──────────┘
        │                                       │
        │ 1. Auth Code Flow + PKCE              │
        ├──────────────────────────────────────>│
        │ 2. User access token (JWT)            │
        │<──────────────────────────────────────┤
        │                                       │
        │ 3. POST /api/agent/chat               │
        │    Authorization: Bearer <user_jwt>   │
        ▼                                       │
 ┌──────────────────────┐                       │
 │  Spring Cloud Gateway│  forwards the header  │
 │       (:9500)        │  unchanged            │
 └──────┬───────────────┘                       │
        │                                       │
        ▼                                       │
 ┌──────────────────────┐                       │
 │   videoclub-agent    │                       │
 │       (:8085)        │                       │
 ├──────────────────────┤                       │
 │ • Resource Server    │ 4. Validate signature │
 │ • TokenRelayService  ├───────────────────────┤ (JWKS, cached)
 │ • Spring AI ChatClient                       │
 └──────┬───────────────┘                       │
        │                                       │
        │ 5. MCP Streamable HTTP                │
        │    Authorization: Bearer <user_jwt>   │
        ▼                                       │
 ┌──────────────────────┐                       │
 │    springboot-sso    │                       │
 │       (:8080)        │                       │
 ├──────────────────────┤                       │
 │ • MCP Server         │ 6. @PreAuthorize evaluates
 │ • @PreAuthorize      │    the caller's own roles
 └──────────────────────┘
```

The gateway needs no `TokenRelay=` filter. That filter relays the token of an
`OAuth2AuthorizedClient` held by the gateway, which would require the gateway to be an OAuth2
*client* with a user session — it is not configured as one. React sends the `Authorization` header
itself and Spring Cloud Gateway forwards request headers by default.

---

## 3. The two identities

One MCP transport carries requests made under two different identities. Keeping them apart is the
core of this design.

| | Identity | Used for | Where it comes from |
|---|---|---|---|
| **Relay path** | the human who made the HTTP request | every `tools/call` | `SecurityContextHolder` → `JwtAuthenticationToken` |
| **Bootstrap path** | `service-account-videoclub-backend` | one `initialize()` handshake at startup | `client_credentials` grant |

### Why a bootstrap identity is needed at all

`/mcp` in `springboot-sso` ends its chain with `anyRequest().authenticated()`, so the MCP handshake
needs *some* token. At startup there is no HTTP request, therefore no `SecurityContext`, therefore
no user. Without a bootstrap credential the handshake returns 401.

### Why the bootstrap identity has no permissions

The service account only has to satisfy `anyRequest().authenticated()`. `initialize()` and
`tools/list` are MCP protocol operations; the `@PreAuthorize` checks live on the **tool methods**,
which are reached only by `tools/call`. Startup discovery therefore works with zero business roles —
confirmed in practice: the agent discovers all 5 tools at boot while
`service-account-videoclub-backend` holds no `movie-permission-read` and no `socio-permission-read`.

**This is a requirement, not a coincidence. Do not grant that service account business roles.** In
the realm those permissions are client roles of `videoclub-frontend`, assigned through the
`administrador` and `cliente` groups; `videoclub-backend` declares `"roles": []`. Granting them
would recreate the shared privileged identity this whole design removed.

Note also that `KeycloakGrantedAuthoritiesConverter` in `springboot-sso` flattens **every** client
entry in `resource_access` into authorities, so roles added to a service account for one purpose
become authorities everywhere. Another reason to leave that client empty.

### The discovery window

`McpClientConfiguration` opens the bootstrap path for exactly one moment:

```java
final AtomicBoolean discoveryWindow = new AtomicBoolean(true);
// ...
.httpRequestCustomizer((builder, method, uri, body, ctx) -> {
    final String token = discoveryWindow.get()
            ? tokenRelayService.getDiscoveryToken()
            : tokenRelayService.getUserBearerToken();
    builder.header("Authorization", "Bearer " + token);
})
// ...
try {
    client.initialize();
} catch (Exception e) {
    log.warn(...);
} finally {
    discoveryWindow.set(false);
}
```

The window is open only while the `@Bean` is being created. No HTTP endpoint is serving yet, so no
user request can pass through it, and the `finally` closes it permanently before the context
finishes refreshing.

`getUserBearerToken()` **throws** when the `SecurityContext` holds no JWT. It does not fall back:

```java
throw new IllegalStateException(
        "No authenticated JWT in the SecurityContext; refusing to call MCP tools without a "
        + "caller identity. MCP tool calls must run under the user's own token.");
```

An earlier revision did fall back to the service account whenever no user was present. That made the
agent's effective identity depend on a condition no caller could observe — any code path running off
the servlet thread would have executed tools as the service account. It was harmless only because
that account holds no business roles, which is a configuration accident rather than a guarantee.
Failing closed makes the guarantee structural.

### Tool discovery is lazy, and that is the recovery path

`SyncMcpToolCallbackProvider` (spring-ai-mcp 2.0.1) caches its callbacks in `cachedToolCallbacks` and
re-issues `tools/list` only when `invalidateCache` is set by an `McpToolsChangedEvent`. `AgentService`
deliberately does **not** capture `ToolCallback[]` in its constructor, so the cache is populated by
the first user request — under that user's own token.

The consequence is a useful one: a failed startup handshake is not fatal. If Keycloak or the MCP
server is unreachable at boot, the warning is logged, the window closes, and the first authenticated
request establishes the session as the user.

---

## 4. How a permission denial travels

It is **not** an HTTP 403.

`AccessDeniedException` is a `RuntimeException`, and Spring AI's `SyncStatelessMcpToolMethodCallback`
catches `RuntimeException` and converts it into `createSyncErrorResult(e)` — a `CallToolResult`
flagged `isError`, carried inside a normal **HTTP 200** JSON-RPC response. The MCP endpoint itself
already authenticated the request; only the *tool* failed.

This is what makes a graceful answer possible: the denial reaches the LLM as tool output it can read
and paraphrase. A real 403 would abort the call before the model ever saw it.

**Do not write client code that switches on a 403 here — there is none.**

---

## 5. Configuration reference

`application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:9091/realms/videoclub}
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:9091/realms/videoclub/protocol/openid-connect/certs}
```

Both properties are set on purpose. With `issuer-uri` alone, startup performs OIDC discovery and
fails when Keycloak is not already running; the explicit `jwk-set-uri` removes that coupling.

The `iss` claim of an incoming token must match `issuer-uri` exactly. A token minted through a
different hostname — `host.docker.internal`, for instance — is rejected.

`SecurityConfiguration` permits `/api/agent/health` anonymously, requires authentication on
`/api/agent/chat` and `/api/agent/tools`, and ends with `anyRequest().authenticated()` so endpoints
added later fail closed.

---

## 6. Verification

Run against a live stack (Keycloak, `springboot-sso`, the gateway and the agent all up). Every call
goes through the gateway on `:9500`, which is what proves the route forwards the header.

| Check | Expected | Observed |
|---|---|---|
| `GET /api/agent/health` without token | 200 | 200 |
| `POST /api/agent/chat` without token | 401 | 401 |
| `GET /api/agent/tools` without token | 401 | 401 |
| `GET /api/agent/tools` with any valid token | 5 tools | `get_movie`, `list_movies`, `search_movies`, `get_socio`, `list_socios` |
| Chat as `usuarioadmin`: *"Listá los socios registrados"* | member list returned | full member registry returned |
| Chat as `usuariocliente`: *"Listá los socios registrados"* | denial explained in prose, HTTP 200 | *"no tengo acceso a la información sobre los socios registrados"* |
| Chat as `usuariocliente`: *"¿Qué películas hay?"* | catalog returned | catalog returned |

The last two rows together are the real proof: the same agent, two identities, two outcomes.

A response of **0 tools** would mean the MCP session was never established — check the startup log
for the handshake warning and the service account's client secret.

---

## 7. Known open issue

The Keycloak client secret for `videoclub-backend` is still committed to the repository, as a
default value in `application.yml` and as a literal in the tracked `.env.example`:

```yaml
client-secret: ${KEYCLOAK_CLIENT_SECRET:dstNSsANvqlaGfZCJa1mcYzP1EBAYP4N}
```

This is the same defect that made the ROPC credentials unacceptable — a working secret surviving in
the image even with an empty `.env` — with a machine credential instead of a human one. Swapping a
user password for a client secret is the acceptable half of that trade; committing it is not.

The fix is to drop the default so startup fails without the variable, put a placeholder in
`.env.example`, and rotate the secret in Keycloak, since it is present from the first commit onward.
