# VideoClub AI Agent Microservice

Spring Boot 4 + Spring AI microservice that acts as an intelligent assistant for the VideoClub ecosystem. It discovers and executes Model Context Protocol (MCP) tools provided by `springboot-sso` under Keycloak SSO authentication.

---

## Features

* **Spring AI & OpenAI**: Natural language processing with tool-calling capabilities.
* **MCP Client**: Dynamic tool discovery and execution via Streamable HTTP (`/mcp`).
* **Keycloak Authentication**: Secure service-to-service and user-authenticated calls.
* **REST Testing Suite**: Ready-to-run `.http` suite in [requests/agent.http](./requests/agent.http).

---

## Documentation

* [SSO Token Propagation (Token Relay) — implemented architecture](./docs/sso-token-propagation.md)
* [Gateway Integration, Token Relay & React Chat Tab (as built)](./docs/gateway-agent-integration.md)
* [HTTP Client Testing Skill](../springboot-sso/.agents/skills/http-client/SKILL.md)

---

## Integration Status

All three integration goals are implemented and verified end-to-end:

1. `/api/agent/**` is exposed through Spring Cloud Gateway (`:9500`).
2. Hardcoded user credentials are gone; the caller's Bearer token is relayed into the MCP tools.
3. The **Asistente AI** tab ships in the `react-sso` frontend.

Open follow-ups are tracked in
[gateway-agent-integration.md §7](./docs/gateway-agent-integration.md).
