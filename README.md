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

## Topología de Red y Arquitectura Docker

El agente se integra al ecosistema de microservicios compartiendo la red Docker **`videoclub_default`** creada por `springboot-sso/docker/services.yaml`.

```mermaid
flowchart TD
    Browser["Browser / React SPA"]

    subgraph DockerNet["Docker Network: videoclub_default"]
        Gateway["videoclub-gateway<br/>(Spring Cloud Gateway :9500)"]
        Agent["agent (:8085)<br/>(videoclub-agent)"]
        Keycloak["video-keycloak<br/>(keycloak:8080)<br/>iss: localhost:9091"]
    end

    subgraph Host["Host Machine (Desarrollo)"]
        Backend["springboot-sso<br/>(Backend & MCP Server :8080)"]
    end

    Browser -->|"http://localhost:9500"| Gateway
    Gateway -->|"Route /api/agent/**"| Agent
    Gateway -->|"host.docker.internal:8080"| Backend
    Agent -->|"JWKS & Token (keycloak:8080)"| Keycloak
    Agent -->|"MCP Tools (host.docker.internal:8080/mcp)"| Backend
```

### Reglas de Comunicación
1. **Gateway ➔ Agent**: El Gateway enruta `/api/agent/**` a `http://agent:8085` usando la resolución DNS interna de Docker.
2. **Agent ➔ Keycloak**: El agente consume los endpoints de JWKS y Token directamente por la red de Docker en `http://keycloak:8080`.
3. **Agent ➔ Backend (MCP)**: El agente invoca las herramientas MCP en `http://host.docker.internal:8080/mcp` saliendo hacia el host donde corre `./mvnw spring-boot:run`.

---

## ¿Por qué es necesario `extra_hosts` en Linux?

En `docker-compose.yml` y `docker-compose.prod.yml` se incluye:
```yaml
extra_hosts:
    - "host.docker.internal:host-gateway"
```

### Razón Técnica
* **En Docker Desktop (macOS / Windows)**: `host.docker.internal` se resuelve de forma automática hacia el host.
* **En Docker nativo sobre Linux**: Este hostname **no existe** por defecto. La directiva `extra_hosts` inyecta en el `/etc/hosts` del contenedor la IP del gateway del bridge Docker (usualmente `172.17.0.1`), permitiendo que el contenedor alcance servicios que corren directamente en el host del desarrollador.

### ¿Cuándo dejará de ser necesario?
Actualmente, `springboot-sso` corre en el host con `./mvnw spring-boot:run`. El día que `springboot-sso` se ejecute dentro de un contenedor en la red `videoclub_default` (por ejemplo con servicio `backend`), la URL pasará a ser `http://backend:8080/mcp` y la directiva `extra_hosts` podrá removerse por completo.

---

## Token Issuer (Keycloak en Docker)

Para que Spring Security en todos los servicios valide los JWTs de manera uniforme provengan del navegador, del gateway o de llamadas internas de red, Keycloak en `springboot-sso/docker/keycloak.yaml` fija su hostname canónico:

```yaml
KC_HOSTNAME: "http://localhost:${KEYCLOAK_PORT:-9091}"
KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "false"
```

* **Validación (`KEYCLOAK_ISSUER_URI`)**: `http://localhost:9091/realms/videoclub` (coincide exactamente con el claim `"iss"` del JWT).
* **Llamadas HTTP salientes (`KEYCLOAK_JWK_SET_URI`, `KEYCLOAK_TOKEN_URL`)**: `http://keycloak:8080/...` (tráfico interno eficiente en Docker).

---

## Entornos Docker Compose (Clase 6)

Siguiendo el principio de **única fuente de la verdad** (*Single Source of Truth*), los archivos Compose no definen valores por defecto (`:-fallback`); toda la parametrización se gestiona de forma explícita en los archivos `.env` y `.env.prod`.

### 1. Desarrollo (`docker-compose.yml`)
* **Nombre de stack**: `name: videoclub` (se adhiere a `videoclub_default`).
* **Hot-reload**: Monta el código fuente local (`.:/app`) y cachea dependencias Maven (`~/.m2:/root/.m2`).
* **Etapa**: `target: dev` sobre `maven:3.9-eclipse-temurin-25`.
* **Comando**: `mvn spring-boot:run`.

```bash
# Preparar variables de entorno
cp .env.template .env
# Configurar OPENAI_API_KEY en .env

# Levantar el entorno de desarrollo
docker compose up -d

# Ver logs
docker compose logs -f agent
```

### 2. Producción (`docker-compose.prod.yml`)
* **Nombre de stack**: `name: videoclub-prod` (namespace aislado).
* **Multi-stage build**: Etapa `target: runtime` con `eclipse-temurin:25-jre-alpine` (~536 MB vs 1.07 GB en dev).
* **Seguridad**: Usuario no-root `app` (`uid 100`).
* **Resiliencia**: `HEALTHCHECK` activo sobre `/api/agent/health` y `restart: always`.
* **Versionado**: Auto-taggeado como `videoclub-agent:${APP_VERSION}`.

```bash
# Preparar variables de producción
cp .env.prod.template .env.prod

# Construir y levantar en producción
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

---

## Verificación End-to-End

### 1. Healthcheck a través del Gateway
```bash
curl -i http://localhost:9500/api/agent/health
# HTTP/1.1 200 OK -> {"status":"UP","agent":"videoclub-agent"}
```

### 2. Consulta al Agente con Token de Usuario
```bash
# Obtener token de Keycloak
USER_TOKEN=$(curl -s -X POST "http://localhost:9091/realms/videoclub/protocol/openid-connect/token" \
  -d "client_id=videoclub-frontend" \
  -d "username=usuariocliente" \
  -d "password=usuariocliente" \
  -d "grant_type=password" | jq -r '.access_token')

# Enviar consulta al agente pasando por el Gateway
curl -s -X POST "http://localhost:9500/api/agent/chat" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "¿Qué películas hay en el catálogo?"}' | jq .
```

---

## Arquitectura Multi-Agente Jerárquica (Spring AI)

El servicio implementa el patrón **Supervisor / Hierarchical Multi-Agent System**:

```mermaid
flowchart TD
    Client["Cliente / Frontend (:9500)"] -->|"Prompt + Token + conversationId"| Supervisor["Agente Orquestador<br/>(Supervisor / Router - AgentService)"]

    subgraph StateManagement["Gestión de Estado y Memoria"]
        Memory[("ChatMemory<br/>(Historial por conversación)")] <--> Supervisor
        Tracker["ExecutionTracker<br/>(Auditoría: agentes y tools)"] <--> Supervisor
    end

    Supervisor -->|"@Tool consultCatalogAgent"| CatalogSubAgent["CatalogSubAgent<br/>(ChatClient experto en catálogo)"]
    Supervisor -->|"@Tool consultMembershipAgent"| MembershipSubAgent["MembershipSubAgent<br/>(ChatClient experto en membresías)"]

    subgraph MCPCatalog["Herramientas MCP de Catálogo"]
        ToolsCatalog["list_movies<br/>get_movie<br/>search_movies"]
    end

    subgraph MCPMembership["Herramientas MCP de Socios"]
        ToolsMembership["get_socio<br/>list_socios"]
    end

    CatalogSubAgent -->|"Ejecuta solo herramientas de películas"| ToolsCatalog
    MembershipSubAgent -->|"Ejecuta solo herramientas de socios"| ToolsMembership
```

### Componentes:
1. **`AgentService` (Orquestador / Supervisor)**:
   - Atiende al usuario, clasifica intenciones y sintetiza respuestas integradas.
   - Responde saludos y preguntas generales directamente sin invocar herramientas ni gastar tokens innecesarios.
   - Cuenta con herramientas de delegación `@Tool` (`consultCatalogAgent`, `consultMembershipAgent`).
2. **`CatalogSubAgent` (Especialista en Catálogo)**:
   - ChatClient aislado con system prompt experto en películas.
   - Conectado exclusivamente a herramientas MCP de películas (`list_movies`, `get_movie`, `search_movies`).
3. **`MembershipSubAgent` (Especialista en Membresías y Socios)**:
   - ChatClient aislado con system prompt experto en socios y permisos.
   - Conectado exclusivamente a herramientas MCP de socios (`get_socio`, `list_socios`).
4. **`ExecutionTracker`**:
   - Registra en tiempo de ejecución tanto los sub-agentes convocados (`agentsInvoked`) como las herramientas ejecutadas (`toolsExecuted`).
