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

* [Frontend Integration & Token Relay Architecture](./docs/sso-token-propagation.md)
* [Next Iteration Roadmap](./docs/next-iteration-plan.md)
* [HTTP Client Testing Skill](../springboot-sso/.agents/skills/http-client/SKILL.md)

---

## Planned for Next Iteration

1. Expose `/api/agent/**` through Spring Cloud Gateway (`:9500`).
2. Remove hardcoded credentials from `.env` and implement dynamic Bearer Token Relay.
3. Add a dedicated **Asistente AI** tab in the `react-sso` frontend.
