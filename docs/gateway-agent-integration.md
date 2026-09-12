# Next Iteration: Gateway Integration, Token Relay & React Chat Tab

This document outlines the scope, architecture, and implementation tasks planned for the next development iteration.

---

## 1. Objectives

1. **Route via API Gateway (`:9500`)**: Expose agent endpoints through Spring Cloud Gateway to provide a unified entry point and eliminate direct frontend-to-agent cross-origin calls. CORS is already centralized there by an existing `globalcors` policy, so this objective inherits it rather than adding it.
2. **Implement Token Relay in `videoclub-agent` (`:8085`)**: Convert the agent into an OAuth2 Resource Server, removing hardcoded credentials (`KEYCLOAK_USERNAME`, `KEYCLOAK_PASSWORD`) from `.env`, and dynamically propagating the user's Bearer JWT to the MCP tools in `springboot-sso` (`:8080`).
3. **Add AI Agent Chat Tab in `react-sso` (`:5173`)**: Add a dedicated "Asistente AI" tab in the React navigation bar, allowing authenticated users to chat with the agent using their active SSO session.

---

## 2. Architecture & Call Flow

```
┌──────────────────────────────────────────────────────────┐
│              Browser (React SPA :5173)                   │
│   New Tab: [Catálogo] [Socios] [Usuarios] [Asistente AI] │
└────────────────────────────┬─────────────────────────────┘
                             │
                             │ POST /api/agent/chat
                             │ Authorization: Bearer <user_jwt>
                             ▼
┌──────────────────────────────────────────────────────────┐
│              Spring Cloud Gateway (:9500)                │
│   Route: /api/agent/** -> host.docker.internal:8085      │
│   Authorization header forwarded by default (no filter)  │
└────────────────────────────┬─────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────┐
│               videoclub-agent (:8085)                    │
│   • Validates JWT via Keycloak JWKS                      │
│   • Relays caller's Bearer token into MCP transport      │
│   • (see Task 2.0: client lifecycle must move to request)│
│   • OpenAI LLM decides tool invocations                  │
└────────────────────────────┬─────────────────────────────┘
                             │
                             │ MCP Streamable HTTP Request
                             │ Authorization: Bearer <user_jwt>
                             ▼
┌──────────────────────────────────────────────────────────┐
│               springboot-sso (:8080)                     │
│   • Executes MCP Tool with caller's security context     │
│   • Enforces @PreAuthorize against user's actual roles   │
│   • Denials return as tool errors in HTTP 200, not 403   │
└──────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Task Breakdown

### Task 1: API Gateway Route Configuration
* **Location**: `springboot-sso/docker/gateway/gateway.yml`.
  The gateway is **not** a module of `springboot-sso`: it is a prebuilt GraalVM native image
  (`registry.gitlab.com/public-unrn/apigateway:1.0`, container `videoclub-gateway-1`) whose
  configuration is mounted as a volume. Edit that file and restart the container — there is no code
  to compile.
* **Action**:
  Add a route alongside the existing `service-catalogo` / `service-socios` entries, matching their
  shape exactly:
  ```yaml
  spring:
    cloud:
      gateway:
        server:
          webflux:
            routes:
              - id: agent-service
                uri: http://host.docker.internal:8085
                predicates:
                  - Path=/api/agent/**
                filters:
                  - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE
  ```
* **Three corrections against the first draft of this plan**:
  1. **The YAML path is `spring.cloud.gateway.server.webflux.routes`**, not
     `spring.cloud.gateway.routes`. Every existing route in the file uses the longer form; the short
     form is silently ignored.
  2. **`uri` must be `http://host.docker.internal:8085`, never `localhost`.** The gateway runs inside
     Docker, so `localhost` resolves to the gateway container itself. Every existing route already
     uses `host.docker.internal:8080` for this reason.
  3. **Do not add `TokenRelay=`.** That filter relays the access token of an `OAuth2AuthorizedClient`
     held by the gateway, which requires the gateway to be configured as an OAuth2 *Client* with a
     user session. This gateway has no `spring.security.oauth2.client` configuration at all. It is
     also unnecessary: React sends the `Authorization` header itself and Spring Cloud Gateway
     forwards request headers by default — proven in production today by `/movies/**` and
     `/api/socios/**`, which already reach an OAuth2 Resource Server through this gateway.
* **CORS**: nothing to do. The gateway already applies a `globalcors` policy to `[/**]`.
* **Validation**: `GET http://localhost:9500/api/agent/health` returning 200 proves only that routing
  works — `/health` is anonymous, so it passes even when token propagation is completely broken. The
  route is validated by Task 2's checks instead.

---

### Task 2: Token Relay & Security in `videoclub-agent`

> **Decide Task 2.0 before writing any code.** The rest of this task depends on it.

#### Task 2.0: Resolve the MCP client lifecycle (blocking design decision)
The MCP client is currently built entirely at startup: `McpClientConfiguration` calls
`client.initialize()` inside the `@Bean`, and the **constructor** of `AgentService` calls
`getToolCallbacks()` and freezes the result into `chatClient.defaultTools(...)`.

At boot there is no HTTP request, so there is no `SecurityContext` and no bearer token. `/mcp` in
`springboot-sso` is not anonymous (`anyRequest().authenticated()`), so the handshake and `tools/list`
both return **401**. The failure is swallowed by a `log.warn`, the service boots anyway, and the
agent ends up with **zero tools permanently** — answering from model knowledge and never calling the
VideoClub.

Swapping the transport customizer does not fix this. Pick one:

| Option | How it works | Trade-off |
|---|---|---|
| **Per-request MCP client** | Build transport, discover tools, and execute inside request scope using the caller's token. | Discovery is authorized as the real user; costs a handshake per request unless pooled. |
| **Split discovery from execution** | A narrow `client_credentials` service account (not ROPC) for boot-time `initialize()`/`tools/list`; the user's token relayed on every `tools/call`. | Cheap startup, but keeps one stored secret — a client secret instead of a human password. |

Either way `AgentService` must stop capturing `ToolCallback[]` in its constructor.

#### Task 2.1: Dependencies
Add `spring-boot-starter-oauth2-resource-server` (and `spring-boot-starter-security`) to `pom.xml`.

#### Task 2.2: Configuration
* Set both `spring.security.oauth2.resourceserver.jwt.issuer-uri` **and** `jwk-set-uri`, mirroring
  `springboot-sso`. With `issuer-uri` alone, startup performs OIDC discovery and fails when Keycloak
  is down.
* Remove `KEYCLOAK_USERNAME` / `KEYCLOAK_PASSWORD` from `.env`, from the tracked `.env.example`, and
  — critically — from `application.yml`, where they currently sit as **hardcoded defaults**:
  ```yaml
  username: ${KEYCLOAK_USERNAME:usuarioadmin}   # survives an empty .env
  password: ${KEYCLOAK_PASSWORD:usuarioadmin}
  ```
  Deleting only the `.env` entries changes nothing.

#### Task 2.3: Code Changes
* Create `SecurityConfiguration.java`. Three endpoints exist, not two — `/api/agent/chat`,
  `/api/agent/tools` and `/api/agent/health`. Require authentication on `chat` and `tools`, permit
  `health` anonymously, and end the chain with `anyRequest().authenticated()` so future endpoints
  fail closed.
* Apply the Task 2.0 decision to `McpClientConfiguration.java` and `AgentService.java`.

#### Validation
* `POST /api/agent/chat` without a token → **401**.
* `POST /api/agent/chat` through the gateway (`:9500`) with an **admin** token → `list_socios`
  succeeds. This is what actually proves the Task 1 route relays the header.
* Same call with a **`usuariocliente`** token → the answer explains the member registry is not
  accessible. Note the transport still returns **HTTP 200**: the denial arrives as an MCP tool error
  result, not a 403. See `sso-token-propagation.md` §3.
* `GET /api/agent/tools` with a valid token → 5 tools. **A response of 0 tools means Task 2.0 was not
  resolved**, even if every other check passes.

---

### Task 3: React Chat Tab (`react-sso`)
* **Navigation**: Add a new navigation tab `Asistente AI` in the main header.
* **Component**: Build `AgentChatView.tsx` / `AgentChatView.jsx`:
  * Interactive chat window (prompt input, message history).
  * Uses existing Keycloak context (`useAuth` / `keycloak.token`) to attach Bearer header.
  * Calls Gateway endpoint: `http://localhost:9500/api/agent/chat`.
  * Renders markdown responses with tool indicator badges (e.g., "Consultando catálogo de películas...").
* **Validation**: End-to-end user chat in browser showing responses rendered from the agent.
