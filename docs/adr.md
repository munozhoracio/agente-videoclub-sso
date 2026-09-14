# Registros de Decisiones de Arquitectura (ADR) — `videoclub-agent`

Este documento registra las decisiones de arquitectura del **agente de IA** de la plataforma VideoClub: contexto, opciones evaluadas, decisión final y consecuencias técnicas.

La numeración **continúa** la del compendio de la plataforma en [`springboot-sso/docs/adr.md`](../../springboot-sso/docs/adr.md), que cubre ADR-001 a ADR-012 (mensajería, socios, API Gateway y servidor MCP). Un mismo número nunca designa dos decisiones distintas en la plataforma.

Documentos de referencia: [`gateway-agent-integration.md`](./gateway-agent-integration.md), [`sso-token-propagation.md`](./sso-token-propagation.md), [`generative-ui-pattern.md`](./generative-ui-pattern.md) y [`agui-streaming-plan.md`](./agui-streaming-plan.md).

---

## Índice de Decisiones

* [ADR-013: Agente como Servicio Autónomo y Cliente MCP Autenticado](#adr-013-agente-como-servicio-autónomo-y-cliente-mcp-autenticado)
* [ADR-014: Token Relay del JWT del Usuario, sin Fallback a la Service Account](#adr-014-token-relay-del-jwt-del-usuario-sin-fallback-a-la-service-account)
* [ADR-015: Identidad de Bootstrap sin Permisos y Ventana de Descubrimiento](#adr-015-identidad-de-bootstrap-sin-permisos-y-ventana-de-descubrimiento)
* [ADR-016: Patrón Supervisor Jerárquico con Sub-Agentes de Dominio](#adr-016-patrón-supervisor-jerárquico-con-sub-agentes-de-dominio)
* [ADR-017: Fail-Fast ante Ausencia de Herramientas MCP del Dominio](#adr-017-fail-fast-ante-ausencia-de-herramientas-mcp-del-dominio)
* [ADR-018: La Denegación de Permisos Viaja como Resultado de Tool dentro de un HTTP 200](#adr-018-la-denegación-de-permisos-viaja-como-resultado-de-tool-dentro-de-un-http-200)
* [ADR-019: Memoria Conversacional en Proceso, Auto-Configurada y Segmentada por `conversationId`](#adr-019-memoria-conversacional-en-proceso-auto-configurada-y-segmentada-por-conversationid)
* [ADR-020: Generative UI Híbrido con Extracción y Validación en el Servidor](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor)
* [ADR-021: Respuesta Bloqueante en vez de Streaming; AG-UI Evaluado y Diferido](#adr-021-respuesta-bloqueante-en-vez-de-streaming-ag-ui-evaluado-y-diferido)
* [ADR-022: Jackson 3 (`tools.jackson`) como Único Mapper Inyectable bajo Spring Boot 4](#adr-022-jackson-3-toolsjackson-como-único-mapper-inyectable-bajo-spring-boot-4)

---

### ADR-013: Agente como Servicio Autónomo y Cliente MCP Autenticado

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El servidor MCP vive dentro de `springboot-sso` (:8080), junto a los servicios de dominio. La opción más corta era alojar el agente en ese mismo proceso: acceso directo a `MovieService` y `SocioService`, sin red de por medio, sin segundo Resource Server.

#### Decisión
Desplegar el agente como un **servicio Spring Boot separado** en el puerto 8085, configurado como **OAuth2 Resource Server**, que alcanza el dominio exclusivamente como **cliente MCP sobre Streamable HTTP**.

#### Justificación
* **El límite de seguridad se ejerce de verdad.** [ADR-009](../../springboot-sso/docs/adr.md) puso `@PreAuthorize` sobre los métodos `@McpTool`. Si el agente viviera en el mismo proceso, la tentación de llamar a `MovieService` directamente eludiría esa capa; siendo un cliente externo, la única puerta es `tools/call`, que sí evalúa el permiso.
* **Aislamiento de credenciales del modelo.** La `OPENAI_API_KEY` y la configuración del proveedor quedan fuera del servicio que expone el catálogo y el padrón.
* **Ciclo de vida independiente.** El agente se reinicia, escala y falla sin arrastrar al backend de negocio.
* **El agente no es un caso especial.** Es un cliente MCP más, igual que Claude Code o Antigravity ([ADR-010](../../springboot-sso/docs/adr.md), [ADR-012](../../springboot-sso/docs/adr.md)). Lo que funciona para él funciona para ellos.

#### Consecuencias
* **Positivas:** La autorización de grano fino no se puede saltear por construcción. Superficie de despliegue separada.
* **Trade-offs:** Un salto de red y una segunda validación de JWT por request. Requiere una ruta propia en el gateway.
* **Derivada:** Obliga a resolver la propagación de identidad entre servicios — es exactamente lo que decide [ADR-014](#adr-014-token-relay-del-jwt-del-usuario-sin-fallback-a-la-service-account).

---

### ADR-014: Token Relay del JWT del Usuario, sin Fallback a la Service Account

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026
* **Reemplaza a:** el esquema inicial con `KEYCLOAK_USERNAME` / `KEYCLOAK_PASSWORD` hardcodeados (Resource Owner Password Credentials), en el que **todas** las llamadas se ejecutaban bajo una única identidad humana compartida.

#### Contexto
El agente necesita invocar herramientas MCP protegidas por `@PreAuthorize`. Las opciones eran tres:

1. **Identidad compartida** (ROPC o service account para todo): simple, pero todas las tools se ejecutan con los permisos de esa cuenta, sin importar quién preguntó.
2. **Token Exchange (RFC 8693)**: el agente intercambia el token del usuario por uno acotado. Correcto, pero agrega un round-trip a Keycloak por request y configuración adicional del realm.
3. **Token Relay**: el agente propaga el JWT del propio llamador.

#### Decisión
Adoptar **Token Relay**. `TokenRelayService.getUserBearerToken()` lee el `JwtAuthenticationToken` del `SecurityContextHolder` y lo inyecta como `Authorization: Bearer` en el transporte MCP. Cuando no hay llamador autenticado, **lanza `IllegalStateException`**: no existe camino de degradación silenciosa hacia la service account.

#### Justificación
* `@PreAuthorize` en las tools evalúa los roles **reales** del usuario. Un `cliente` y un `administrador` obtienen respuestas distintas del mismo agente, sin lógica de permisos duplicada en el agente.
* Cero credenciales humanas en configuración.
* **Por qué el `throw` y no un fallback:** una revisión previa caía en silencio a la service account cuando el `SecurityContext` estaba vacío. Eso hacía que la identidad dependiera de una condición que ningún llamador podía observar — cualquier ejecución fuera del hilo del servlet corría como la service account. Era inofensivo sólo porque esa cuenta no tiene roles de negocio: un accidente de configuración, no una garantía. Fallar es la única opción que no miente.

#### Consecuencias
* **Positivas:** Autorización de grano fino de punta a punta, sin identidad privilegiada compartida.
* **Negativas — la que importa:** `SecurityContextHolder` usa `MODE_THREADLOCAL`. La delegación a sub-agentes funciona porque es **síncrona sobre el hilo del servlet**. Cualquier ejecución asíncrona o en paralelo (`CompletableFuture`, pools, streaming reactivo) pierde el contexto y el `throw` interrumpe la llamada.
* **Mitigación conocida:** envolver ejecutores con `DelegatingSecurityContextExecutorService`, o propagar el token explícitamente como parámetro. Esta restricción es la razón directa de [ADR-021](#adr-021-respuesta-bloqueante-en-vez-de-streaming-ag-ui-evaluado-y-diferido).
* **Deuda abierta:** el `client-secret` de `videoclub-backend` todavía figura como valor por defecto en `application.yml`. Debe rotarse y dejar de tener default, para que el arranque falle sin la variable.

---

### ADR-015: Identidad de Bootstrap sin Permisos y Ventana de Descubrimiento

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El endpoint `/mcp` de `springboot-sso` cierra su cadena con `anyRequest().authenticated()`, así que el handshake `initialize()` necesita **algún** token. En el arranque no hay request HTTP, por lo tanto no hay `SecurityContext` ni usuario. Sin credencial de bootstrap el handshake devuelve 401.

Pero un mismo transporte MCP sirve a dos clases de request con dos identidades distintas, y mezclarlas reintroduce el problema que [ADR-014](#adr-014-token-relay-del-jwt-del-usuario-sin-fallback-a-la-service-account) eliminó.

#### Decisión
Usar un token `client_credentials` de `service-account-videoclub-backend` **exclusivamente** para el handshake de arranque, gobernado por un flag `discoveryWindow` que se cierra en un bloque `finally` dentro de la creación del `@Bean`, antes de que el contexto termine de refrescar.

Adicionalmente: **esa service account no debe tener ningún rol de negocio.**

#### Justificación
* La ventana está abierta un único instante, mientras el bean se construye. No hay endpoint sirviendo todavía, así que ningún request de usuario puede colarse.
* La service account sólo tiene que satisfacer `anyRequest().authenticated()`. `initialize()` y `tools/list` son operaciones de protocolo; los `@PreAuthorize` viven en los métodos de las tools, alcanzados sólo por `tools/call`. **El descubrimiento funciona con cero permisos de negocio** — verificado: el agente descubre las 5 tools con `videoclub-backend` declarando `"roles": []`.
* Darle roles a esa cuenta recrearía la identidad privilegiada compartida. Y hay un motivo extra: `KeycloakGrantedAuthoritiesConverter` aplana **todas** las entradas de `resource_access` en authorities, así que un rol agregado para un propósito se vuelve authority en todos.

#### Consecuencias
* **Positivas:** Arranque autosuficiente sin abrir un camino de escalación.
* **Diseñada, no accidental:** un handshake fallido al inicio **no es fatal**. Se loguea un `WARN`, la ventana se cierra igual, y como `SyncMcpToolCallbackProvider` descubre las tools de forma perezosa —en el primer request— la sesión se restablece con el token del primer usuario. Si Keycloak o el MCP no están arriba al bootear, el sistema se recupera solo.
* **Requisito permanente y frágil ante operación manual:** nada en el código impide que alguien asigne roles a esa service account desde la consola de Keycloak. Es una invariante documentada, no verificada automáticamente.

---

### ADR-016: Patrón Supervisor Jerárquico con Sub-Agentes de Dominio

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
La opción directa era un agente único con las 5 herramientas MCP expuestas a un solo modelo generalista.

#### Decisión
Adoptar un **patrón Supervisor jerárquico**:

* **`AgentService`** — supervisor/router. Mantiene el contexto, responde saludos y preguntas generales sin invocar tools, y delega vía métodos `@Tool` (`consultCatalogAgent`, `consultMembershipAgent`).
* **`CatalogSubAgent` / `MembershipSubAgent`** — heredan de `AbstractDomainSubAgent`, con prompt propio y **whitelist de tools** (`list_movies`, `get_movie`, `search_movies`, `create_movie` / `get_socio`, `list_socios`).

#### Justificación
* **Superficie de decisión acotada.** Cada modelo elige entre 2–4 herramientas de un solo dominio, no entre 5 de dos dominios. Menos confusión de tools, prompts más cortos y específicos.
* **Trazabilidad.** La delegación es un tool call observable: `ExecutionTracker` registra qué sub-agente se activó, no sólo qué tool se ejecutó.
* **Extensibilidad.** Un dominio nuevo es una subclase con su whitelist, sin tocar el supervisor.

#### Consecuencias
* **Positivas:** Aislamiento por dominio y observabilidad de la decisión de ruteo.
* **Trade-offs:** Cada delegación es **una llamada adicional al modelo** — más latencia y más costo por turno que un agente plano.
* **Negativa — la que importa:** los sub-agentes son **stateless**: no ven la memoria conversacional. Una pregunta anafórica ("¿y cuál tiene más stock?") llega vacía de contexto.
* **Mitigación:** el prompt del supervisor incluye una regla crítica de delegación — reformular la consulta en el `@ToolParam` de manera **completamente auto-contenida**, resolviendo pronombres y referencias previas. Es una obligación del prompt, no del compilador: si el modelo la incumple, el sub-agente responde sobre una pregunta incompleta.

---

### ADR-017: Fail-Fast ante Ausencia de Herramientas MCP del Dominio

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
`AbstractDomainSubAgent` filtra los `ToolCallback` descubiertos contra su whitelist. Ese filtro puede quedar vacío por causas reales: handshake MCP fallido, `springboot-sso` caído, o una tool renombrada del otro lado.

Un modelo sin herramientas **no falla**: responde igual, inventando películas y socios con total fluidez.

#### Decisión
Si tras el filtrado quedan **cero** callbacks, loguear `ERROR` y lanzar `IllegalStateException` inmediatamente, sin llamar al modelo. El mensaje incluye las tools esperadas y las efectivamente disponibles.

#### Justificación
* La degradación silenciosa en un sistema con LLM no produce una respuesta peor: produce una **respuesta falsa indistinguible de una correcta**. Un usuario no puede auditar si "Matrix, $150" salió del catálogo o del modelo.
* El diagnóstico queda en el mensaje: comparar esperadas contra disponibles identifica la causa sin depurar.

#### Consecuencias
* **Positivas:** Una caída de MCP se manifiesta como error visible (HTTP 503 al usuario) en vez de una mentira plausible.
* **Trade-offs:** Menor disponibilidad aparente. Es deliberado: para este dominio, no responder es preferible a responder mal.
* **Alcance:** cubre la ausencia **total** de tools del dominio. No cubre que el modelo decida no invocarlas teniéndolas disponibles.

---

### ADR-018: La Denegación de Permisos Viaja como Resultado de Tool dentro de un HTTP 200

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Cuando `@PreAuthorize` rechaza un `tools/call`, la intuición dice que el agente debería recibir un **HTTP 403**. No es lo que ocurre: `AccessDeniedException` es una `RuntimeException`, y `SyncStatelessMcpToolMethodCallback` de Spring AI la captura y la convierte en un `CallToolResult` marcado `isError`, transportado dentro de una respuesta JSON-RPC con **HTTP 200**. El endpoint MCP ya autenticó el request; lo que falló fue la *herramienta*.

#### Decisión
**Adoptar ese comportamiento en vez de traducirlo.** La denegación llega al LLM como salida de tool que puede leer y parafrasear. Además, `TrackingToolCallback` intercepta la excepción, la registra en `ExecutionTracker.toolsDenied` y la deja propagar para que el modelo genere su explicación.

El payload de `/api/agent/chat` expone `toolsDenied` junto a `toolsExecuted`, y el frontend los distingue visualmente (`🚫 list_socios (sin permiso)`).

#### Justificación
* Un 403 real abortaría la llamada **antes** de que el modelo viera nada: el usuario recibiría un error crudo en vez de "no tenés permiso para consultar el padrón de socios".
* Sin `toolsDenied`, una denegación es indistinguible de un resultado vacío. El usuario podría creer que no hay socios, cuando lo que pasa es que no puede verlos. La distinción es una garantía de la UI, no del prompt.

#### Consecuencias
* **Positivas:** Denegaciones explicadas en lenguaje natural, con trazabilidad exacta de qué tool se bloqueó.
* **Negativas:** Contradice la intuición HTTP. **No escribir código cliente que haga `switch` sobre un 403 acá — no existe.**
* **Dependencia:** el comportamiento es de la implementación de Spring AI 2.0.1, no un contrato del protocolo MCP. Un cambio de versión podría alterarlo.

---

### ADR-019: Memoria Conversacional en Proceso, Auto-Configurada y Segmentada por `conversationId`

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El asistente necesita coherencia entre turnos. Las opciones iban desde no tener memoria (cada turno aislado) hasta persistirla en PostgreSQL o Redis.

#### Decisión
Usar la memoria **auto-configurada por Spring AI**: `ChatMemoryAutoConfiguration` provee `MessageWindowChatMemory` sobre `InMemoryChatMemoryRepository`. El proyecto **no declara ningún bean propio**. Se aplica vía `MessageChatMemoryAdvisor`, segmentada por el `conversationId` que envía el cliente, y se expone `DELETE /api/agent/chat/memory?conversationId=...` para reiniciarla.

En el turno 0 se siembra la memoria con el perfil del usuario (nombre, email, roles) tomado del JWT, para que el asistente lo reconozca desde el saludo.

#### Justificación
* Para un taller, la memoria en proceso es suficiente y elimina una dependencia de infraestructura.
* Segmentar por `conversationId` —y no por usuario— permite varias conversaciones paralelas del mismo usuario y un "nueva conversación" limpio.
* Sembrar el perfil desde el JWT evita que el modelo tenga que preguntar quién es el usuario, y mantiene una única fuente de verdad de identidad: el token.

#### Consecuencias
* **Positivas:** Cero infraestructura adicional; multi-turno coherente.
* **Negativas:** La memoria **muere con el proceso** y **no se comparte entre instancias**. El agente no es horizontalmente escalable sin sticky sessions mientras esto siga así.
* **Vuelta atrás / evolución:** declarar un `ChatMemoryRepository` propio (JDBC o Redis) desplaza la auto-configuración sin tocar `AgentService`, porque la dependencia es sobre la interfaz `ChatMemory`.
* **A tener en cuenta:** el `conversationId` lo elige el cliente. Hoy no se valida que pertenezca al usuario autenticado; dos usuarios que enviaran el mismo id compartirían historial.

---

### ADR-020: Generative UI Híbrido con Extracción y Validación en el Servidor

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026
* **Reemplaza a:** la primera versión de este patrón, que parseaba el bloque estructurado en el **navegador**.

#### Contexto
Presentar un catálogo como viñetas markdown (`- Título: X, Precio: Y`) desaprovecha el frontend. Reemplazar todo el diálogo por componentes es el antipatrón opuesto. La regla adoptada:

> El lenguaje natural conversa, explica y razona; la UI estructurada presenta entidades y habilita acciones.

El modelo emite la data como un bloque ` ```json:movies ` dentro de su prosa. La primera implementación lo parseaba en React, lo que puso el contrato de UI en el mismo canal que el texto libre y volvió visible para el usuario cada falla del modelo: un resultado vacío filtraba el fence crudo a la pantalla, un segundo bloque quedaba sin consumir porque la regex no era global, y un bloque `json:socios` era capturado por la rama de movies y mostrado como JSON roto.

#### Decisión
Mantener el contrato híbrido, pero **extraer y validar en el servidor** (`GenerativeUiExtractor`), bajo un invariante único:

> **Todo fence reconocido se elimina siempre del texto**, parsee o no, valide o no, sea su `kind` soportado o no.

Un artifact se produce sólo si el bloque parsea **y** deja al menos un ítem renderizable. El payload expone `artifacts: List<UiArtifact>`, con `kind` como discriminador.

#### Justificación
* Las tres fallas dejan de ser posibles **por construcción**, no por disciplina: el navegador nunca ve un fence.
* La validación ocurre una vez, en Java, contra un tipo real (`MovieItem`), y valida **todos** los ítems, no sólo el primero.
* `price` declarado `BigDecimal` normaliza el `"150.00"` string que pide el prompt, de modo que el tipo `Movie.price?: number` del frontend deja de mentir.
* Un cliente CLI recibe prosa limpia. Antes eso dependía de que el bloque fuera markdown válido; ahora está garantizado.
* Un dominio nuevo es un `kind` nuevo, **nunca una regex nueva**.

#### Consecuencias
* **Positivas:** El frontend no parsea nada; se eliminó `parseGenerativeContent` por completo. 10 tests fijan cada falla histórica.
* **Negativas — la que importa:** el dato **sigue pasando por el modelo**, que lo retipea desde la salida de la tool MCP. La validación atrapa lo malformado y lo incompleto, pero **no puede detectar un `id` o un `price` transcripto mal**. El patrón reduce la superficie de error, no la elimina.
* **Decisión consciente sobre `kind` no soportado:** se descarta con `WARN` en vez de mostrarse. Mostrar JSON crudo a un usuario final nunca es correcto; el log es la señal para agregar la rama faltante. Es pérdida de datos silenciosa desde la perspectiva del usuario.
* **Evolución natural:** construir el artifact desde la salida **cruda** de la tool, interceptada en `TrackingToolCallback`, para que el modelo sólo escriba prosa. Elimina ids y precios alucinados.

---

### ADR-021: Respuesta Bloqueante en vez de Streaming; AG-UI Evaluado y Diferido

* **Estado:** Aceptado — alternativa evaluada y **diferida**
* **Fecha:** Septiembre 2026

#### Contexto
`AgentService` usa `.call().content()`: el usuario espera el turno completo. Con un supervisor que delega en un sub-agente que invoca tools MCP, eso son varios segundos de spinner. El streaming (SSE) es la mejora obvia, y el protocolo **AG-UI** es el estándar emergente para hacerlo con eventos tipados (`TEXT_MESSAGE_CONTENT`, `TOOL_CALL_*`, `STATE_SNAPSHOT`).

Se evaluaron tres caminos, con verificación empírica:

1. **Wrapper AG-UI publicado** (`io.github.pascalwilbrink.ag-ui.community:spring-ai:1.0.1`) — **descartado**. Está compilado contra Spring AI 1.0.1 y referencia `PromptChatMemoryAdvisor`, clase **eliminada en Spring AI 2.x**. Con nuestro BOM en 2.0.1, Maven resuelve 2.0.1 y el wrapper explota en runtime. (Las coordenadas `com.ag-ui.community:spring-ai:1.0.1` que circulan en artículos **no existen** en Maven Central.)
2. **Fork de `Work-m8/ag-ui-4j`** — **viable, medido**: tres ediciones (bump de `spring-ai` a 2.0.1, swap de `PromptChatMemoryAdvisor` por `MessageChatMemoryAdvisor`, bump de Spring 6.2.9→7.0.9 y Boot 3.4.3→4.1.1) producen `BUILD SUCCESS` y 175 tests verdes sobre JDK 25.
3. **Adaptador propio** sobre `com.ag-ui.community:java-core:0.1.1` + `java-server:0.1.1` — librería oficial del protocolo, cero dependencias de Spring.

#### Decisión
**Mantener la respuesta bloqueante por ahora.** El plan completo, con las dos rutas y su secuenciación, queda registrado en [`agui-streaming-plan.md`](./agui-streaming-plan.md) en estado *proposed*.

#### Justificación
* **La razón dominante no es de UI, es de seguridad.** [ADR-014](#adr-014-token-relay-del-jwt-del-usuario-sin-fallback-a-la-service-account) depende de `SecurityContextHolder`, un `ThreadLocal`. Bajo `.stream()`, la suscripción —y con ella cada tool call— corre en el hilo del event loop del cliente HTTP, donde el contexto está vacío y `getUserBearerToken()` **lanza**. Migrar a streaming sin resolver primero la propagación explícita de identidad rompe el Token Relay.
* La mejora de UX es real pero incremental; la regresión de seguridad sería estructural.
* El protocolo AG-UI todavía no es estable: el cliente TS está en `0.0.x`, el lado Java en `0.1.x`, y el cliente TS incluye shims llamados `BackwardCompatibility_0_0_39/45/47/57`.

#### Consecuencias
* **Positivas:** El Token Relay se mantiene correcto y simple. El campo `artifacts` de [ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor) mapea uno a uno a eventos `STATE_SNAPSHOT` / `CUSTOM`, así que el trabajo hecho no se tira al migrar.
* **Negativas:** El usuario espera el turno completo sin feedback incremental.
* **Condición para reabrir:** resolver primero la propagación explícita del token (capturarlo en el controller sobre el hilo del servlet y pasarlo como parámetro), y recién después el transporte.

---

### ADR-022: Jackson 3 (`tools.jackson`) como Único Mapper Inyectable bajo Spring Boot 4

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Al implementar [ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor) se inyectó `com.fasterxml.jackson.databind.ObjectMapper`. Compiló sin problemas y los tests unitarios pasaron en verde — pero el contexto de Spring falló al arrancar:

```
Consider defining a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' in your configuration.
```

Spring Boot 4 auto-configura **Jackson 3**. En `spring-boot-jackson-4.1.1.jar`, `JacksonAutoConfiguration` declara `tools.jackson.databind.json.JsonMapper` y **ningún** bean de `ObjectMapper`. Lo traicionero es que Jackson 2 **sigue en el classpath**: `spring-boot-starter-jackson:4.1.1` arrastra `com.fasterxml.jackson.core:jackson-databind:2.21.5` **y** `tools.jackson.core:jackson-databind:3.1.5`.

#### Decisión
En este proyecto, todo componente que necesite un mapper **inyecta `tools.jackson.databind.json.JsonMapper`**, con `tools.jackson.core.type.TypeReference`.

#### Justificación
* Es el único mapper que existe como bean. Inyectar `ObjectMapper` no es una preferencia de estilo: no arranca.
* Las **anotaciones siguen en `com.fasterxml.jackson.annotation`** (`jackson-databind` 3.1.5 no tiene paquete `tools/jackson/annotation`), así que `@JsonIgnoreProperties` y compañía funcionan sin cambios con el mapper de Jackson 3.
* `readValue(String, TypeReference<T>)` existe igual que en Jackson 2; cambia el paquete y que `JacksonException` es unchecked.

#### Consecuencias
* **Positivas:** Alineado con la auto-configuración del framework, sin beans manuales.
* **Excepción documentada:** `TokenRelayService` usa `ObjectMapper` de Jackson 2, pero lo **construye** (`new ObjectMapper()`), no lo inyecta. Por eso nunca falló y se deja como está.
* **Negativa — la lección:** los tests unitarios que construyen el mapper a mano **no detectan este fallo**: pasan verdes mientras el contexto revienta. Verificar el arranque real es parte de la definición de terminado, no un extra.
