# VideoClub HTTP Suites Reference

This document describes the two primary `.http` suites in the VideoClub ecosystem and their respective endpoints.

---

## 1. Backend, SSO & Gateway Suite (`postman/video_club.http`)

Location: `springboot-sso/postman/video_club.http`

### Variables
* `@host`: Target host (default `localhost`)
* `@keycloak_port`: Port `9091`
* `@backend_port`: Port `8080` (Direct `springboot-sso`)
* `@gateway_port`: Port `9500` (Spring Cloud Gateway)
* `@access_token`: Extracted dynamically from `login` (`login.response.body.access_token`)
* `@access_token_api`: Extracted dynamically from `login_api` (`login_api.response.body.access_token`)

### Requests Catalog
1. **Authentication (`:9091`)**:
   * `login`: Password grant (`usuarioadmin` / `usuarioadmin`) via `videoclub-frontend` client.
   * `login_api`: Client credentials grant via `videoclub-backend` client.
2. **Direct Backend (`:8080`)**:
   * `backend_movies_list`: `GET /movies`
   * `backend_movies_create`: `POST /movies`
   * `backend_socios_list`: `GET /api/socios`
   * `backend_users_list`: `GET /api/users`
3. **API Gateway (`:9500`)**:
   * `gateway_movies_list`: `GET /movies`
   * `gateway_socios_list`: `GET /api/socios`
   * `gateway_users_list`: `GET /api/users`
   * `gateway_user_create`: `POST /api/users`
4. **Events & Broker Diagnostics**:
   * `broker_test_event`: `POST /api/test/rabbitmq/event`

---

## 2. AI Agent & MCP Suite (`requests/agent.http`)

Location: `videoclub-agent/requests/agent.http`

### Variables
* `@host`: Target host (default `localhost`)
* `@port`: Port `8085` (`videoclub-agent`)
* `@base_url`: `http://{{host}}:{{port}}`

### Requests Catalog
1. **Diagnostics**:
   * `agent_health`: `GET /api/agent/health` — Confirms microservice status `UP`.
   * `agent_list_tools`: `GET /api/agent/tools` — Lists the 5 discovered MCP tools from `springboot-sso` (`get_movie`, `list_movies`, `search_movies`, `get_socio`, `list_socios`).
2. **LLM Chat & Tool Invocations**:
   * `agent_chat_list_movies`: Asks for available movies; agent calls `list_movies`.
   * `agent_chat_search_movie`: Queries for specific movies; agent calls `search_movies`.
   * `agent_chat_get_movie_by_id`: Queries movie by ID; agent calls `get_movie`.
   * `agent_chat_list_socios`: Inquires about socios; agent calls `list_socios`.
   * `agent_chat_get_socio_by_id`: Queries socio by ID; agent calls `get_socio`.
