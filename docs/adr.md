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
* [ADR-023: Imagen Nativa GraalVM como Etapa Opcional y no como Reemplazo](#adr-023-imagen-nativa-graalvm-como-etapa-opcional-y-no-como-reemplazo)
* [ADR-024: El Artefacto Generative UI se Captura antes del Orquestador](#adr-024-el-artefacto-generative-ui-se-captura-antes-del-orquestador)

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

* **Estado:** Aceptado e Implementado — **enmendado por [ADR-024](#adr-024-el-artefacto-generative-ui-se-captura-antes-del-orquestador)**
* **Fecha:** Septiembre 2026
* **Reemplaza a:** la primera versión de este patrón, que parseaba el bloque estructurado en el **navegador**.
* **Enmendado por:** [ADR-024](#adr-024-el-artefacto-generative-ui-se-captura-antes-del-orquestador). El invariante y el contrato `artifacts` siguen vigentes; lo que cambió es **dónde** se extrae el bloque. Lo que este ADR describe como extracción sobre el texto final del orquestador hoy es sólo un fallback.

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

#### Revisión del 15 de septiembre de 2026 — la decisión no cambia, la evidencia se refuerza

Se reevaluó el ecosistema a pedido, resolviendo `repo1.maven.org` y leyendo los jars publicados. **Ningún hallazgo modifica la decisión; tres la sostienen mejor.**

* **La doc oficial del SDK Java prescribe un tercer juego de coordenadas que tampoco existe.** [`docs/sdk/java/overview.mdx`](https://github.com/ag-ui-protocol/ag-ui/blob/main/docs/sdk/java/overview.mdx) indica `com.ag-ui:core|client|http:0.0.1`; esas rutas dan **404**. El namespace real sigue siendo `com.ag-ui.community`. Además esa página documenta sólo la superficie **cliente**: el lado servidor que necesitaríamos ni figura.
* **Apareció una integración de terceros que parece resolverlo y no lo hace.** [`JavaAIDev/spring-ai-ag-ui`](https://github.com/JavaAIDev/spring-ai-ag-ui) anuncia Spring AI 2.0.0 + AG-UI, pero es un proyecto de ejemplo (6 commits, sin releases) y su `pom.xml` combina el BOM 2.0.0 con `io.github.pascalwilbrink.ag-ui.community:spring-ai:1.0.1` — exactamente la mezcla que la opción 1 descarta. Verificado en su bytecode: **4 referencias a `PromptChatMemoryAdvisor`, alojadas en `SpringAIAgent`**, la clase de entrada de la integración. Y esa clase está **ausente tanto en `spring-ai-client-chat:2.0.0` como en `2.0.1`**. Compila —`javac` no resuelve referencias internas de una dependencia binaria— y falla al cargar la clase. *Un README que dice «usa Spring AI 2.0.0» describe el BOM declarado, no una integración funcionando.*
* **La opción 3 se midió y es más sólida de lo que se creía.** `com.ag-ui.community:java-core:0.1.1` pesa 69 KB con 63 clases y **ninguna dependencia de runtime**; `java-server:0.1.1` pesa 12,7 KB con 11 clases y depende sólo de `java-core`. Target `release=17`. Cero Spring, cero Reactor, cero Jackson: **por construcción no puede chocar con Spring AI 2.x**, que es precisamente cómo falla el wrapper. A cambio, entrega poco: formato de cable y tipos de evento; todo el mapeo del agente sigue siendo nuestro.
* **Madurez, sin maquillaje:** el proyecto lleva **dos releases en toda su historia** (`0.1.0` y `0.1.1`, ambos del 9 de septiembre de 2026).
* **Lo que sí mejoró de nuestro lado:** [ADR-024](#adr-024-el-artefacto-generative-ui-se-captura-antes-del-orquestador) movió la captura del artefacto a `OrchestratorTools.captureArtifacts`, que corre apenas retorna un sub-agente. Ese es exactamente el punto del flujo donde se emitiría un `STATE_SNAPSHOT` incremental: el gancho que necesita el streaming ya existe, y llegó por otro motivo.

El detalle completo, con tablas y rutas verificadas, queda en el apéndice de [`agui-streaming-plan.md`](./agui-streaming-plan.md).

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

---

### ADR-023: Imagen Nativa GraalVM como Etapa Opcional y no como Reemplazo

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El servicio se despliega como un `.jar` sobre `eclipse-temurin:25-jre-alpine` y arranca en **2,8–3,5 s**. Ese costo se paga en cada reinicio, en cada redeploy y en cada escalado. GraalVM permite compilar el mismo código a un ejecutable nativo que arranca en centésimas de segundo, a cambio de asumir un modelo de **mundo cerrado**: todo lo que se resuelva por reflexión debe declararse en tiempo de compilación.

La vía documentada por Spring (`./mvnw spring-boot:build-image -Pnative`) no era aplicable: ese goal arma la imagen con Paketo buildpacks y necesita el daemon de Docker, algo imposible dentro de un build multi-stage.

#### Decisión
Agregar al `Dockerfile` existente **dos etapas opcionales** —`native-build` y `native-runtime`— que **conviven** con `dev`, `build` y `runtime` en lugar de reemplazarlas. La selección se hace por variable de entorno (`BUILD_TARGET`, con default `runtime`), de modo que el flujo JVM no cambia.

La compilación se hace a mano en dos pasos y en este orden: `package` (que dispara el `process-aot` que el perfil `native` del parent agrega a `spring-boot-maven-plugin`) y luego `native:compile-no-fork` (el goal `native:compile` forkea el ciclo `package` y repetiría todo el primer paso).

#### Justificación
* **Arranque ~26× más rápido**: 0,10–0,12 s contra 2,8–3,5 s, medido sobre el mismo commit.
* **Ambos modos siguen disponibles.** Nativo es un modelo de ejecución distinto, no una mejora gratuita; el equipo elige por entorno en vez de quedar casado con uno.
* **Los hints son inertes en la JVM.** `NativeRuntimeHints` solo se procesa durante AOT (toda la maquinaria de `@ImportRuntimeHints` vive bajo `org.springframework.context.aot`), así que el soporte nativo no impone costo ni riesgo al camino JVM.
* **El runtime nativo debe ser glibc.** La imagen de GraalVM es Oracle Linux; un binario linkeado contra glibc no arranca sobre Alpine (musl). De ahí `debian:bookworm-slim`.

#### Consecuencias
* **Positivas:** Arranque casi instantáneo, sin calentamiento de JIT y con menor consumo de memoria en ejecución. Habilita escalar a cero sin penalizar al primer request.
* **Trade-off — compilar es caro:** ~2 min 30 s y un pico de **~8,9 GB de RAM**. Con poca memoria en el daemon, el build muere sin mensaje útil. El ciclo de feedback para depurar se vuelve lento.
* **Trade-off — el tamaño casi no baja:** 452 MB contra 536 MB de la imagen JVM; el binario solo pesa 244 MB. La imagen nativa **no** es automáticamente liviana.
* **Negativa — la clase de fallo que aparece:** en la primera puesta a punto, **cuatro** errores distintos, todos con la misma raíz: *falla lo que el compilador no puede ver estáticamente*. En orden de ejecución:
  1. **Default de constante de interfaz.** `BaseAdvisor.DEFAULT_SCHEDULER = Schedulers.boundedElastic()` se resolvía a `null` → `scheduler cannot be null`. Se corrige pasando el scheduler explícitamente en código propio.
  2. **Escaneo de anotaciones por reflexión.** Spring AI busca `@Tool` recorriendo los métodos del objeto → `No @Tool annotated methods found`. La clase cargaba, pero sus métodos eran invisibles.
  3. **Deserialización manual con un mapper propio.** `GenerativeUiExtractor` liga `List<MovieItem>` fuera de toda firma que el AOT pueda leer → `Record components not available`.
  4. **Tipo borrado por un comodín.** El handler está declarado `ResponseEntity<?>`, así que `ChatResponse` no aparece en ninguna firma y el AOT nunca lo registra. El comodín es deliberado (el mismo handler devuelve cuerpos `Map` de error), por lo que el hint es el arreglo correcto y no angostar el tipo de retorno.
* **Lo que nunca falló:** los tipos que **sí** están declarados en una firma de controller (como `ChatRequest` en `@RequestBody`), porque el AOT los ve y los registra solo. El límite no es la reflexión en abstracto, sino la visibilidad estática.
* **Práctica derivada:** verificar un hint leyendo `target/classes/META-INF/native-image/ar.unrn/videoclub-agent/reachability-metadata.json` **antes** de compilar. Convierte un ciclo de dos minutos y medio en uno de segundos. Spring Boot 4 genera ese archivo unificado; ya no existen los `reflect-config.json` separados.
* **Alcance:** ninguno de estos fallos afecta a la JVM. Son el precio exacto del arranque en 0,1 s.

---

### ADR-024: El Artefacto Generative UI se Captura antes del Orquestador

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026
* **Enmienda a:** [ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor). No cambia el contrato `artifacts` ni el invariante del fence; cambia **el punto del flujo donde se extrae**.

#### Contexto
[ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor) sacó el parseo del navegador y lo puso en el servidor, sobre el texto **final** del orquestador. Quedó un eslabón sin cubrir: el bloque emitido por el sub-agente todavía tenía que **atravesar al orquestador** para llegar a ese punto. La única defensa era una regla de prompt:

> «PRESERVACIÓN DE ARTEFACTOS GENERATIVE UI: … debés PRESERVAR intacto ese bloque al final.»

En producción dejó de cumplirse. Las tarjetas desaparecieron sin ningún error: `artifacts` llegaba vacío y el usuario veía viñetas markdown.

El diagnóstico se hizo instrumentando `consultCatalogAgent` para registrar lo que devuelve el sub-agente **antes** de que el orquestador lo vea:

* Log del sub-agente: `[fence present: true]`, con un bloque ` ```json:movies ` completo y bien formado (5 ítems, todos los campos).
* Respuesta HTTP final: `"artifacts":[]` y el listado reescrito como `- **Matrix I** (Ciencia Ficción) - Precio: $200.00`.

El bloque existía y se perdía **entre** el sub-agente y la respuesta. El único paso intermedio es el LLM orquestador. Se descartó además que fuera una regresión de [ADR-023](#adr-023-imagen-nativa-graalvm-como-etapa-opcional-y-no-como-reemplazo): el fallo se reproduce idéntico en JVM y sobre un `HEAD` limpio, sin ninguno de los cambios de imagen nativa.

#### Decisión
Extraer el bloque en `OrchestratorTools`, en el instante en que el sub-agente retorna, mediante el helper `captureArtifacts`:

1. Se extrae con el mismo `GenerativeUiExtractor`.
2. Los artifacts se registran en `ExecutionTracker` (que ya acumula agentes y tools del turno).
3. **Al orquestador se le devuelve sólo la prosa, ya sin el fence.**

`ExecutionTracker` pasa a ser la fuente autoritativa de `artifacts`. La extracción sobre el texto final se conserva por dos razones: sostener el invariante de que ningún fence llegue al cliente como prosa, y cubrir como fallback el camino sin delegación.

La regla del prompt del orquestador se reescribió: pedía preservar un bloque que ya no recibe, y una instrucción imposible de cumplir es peor que ninguna. Ahora informa que las tarjetas las monta el sistema, que no puede romperlas, y que no repita atributos en el texto.

#### Justificación
* **«Preservá esto intacto» es un pedido, no una garantía.** El orquestador es un modelo de lenguaje cuyo trabajo *es* reescribir prosa; pedirle fidelidad literal compite contra su comportamiento por defecto. Funcionó un tiempo y dejó de funcionar sin que nadie tocara una línea: así fallan los pedidos a un LLM, en silencio y sin stack trace.
* **No se puede destruir lo que nunca se recibió.** El fallo deja de ser improbable para volverse imposible por construcción — exactamente el criterio con el que [ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor) justificó mover el parseo al servidor. Esto aplica el mismo principio al eslabón que había quedado afuera.
* **Sin nada específico de GraalVM.** Es Java puro y se comporta igual en JVM y en imagen nativa; los problemas de diseño se arreglan con diseño, no con hints.
* **Se vuelve testeable sin gastar en el modelo.** `captureArtifacts` es una función sobre un `String`: un test puede fijar que el artifact queda en el tracker y que el texto sale limpio, sin llamar a OpenAI.

#### Consecuencias
* **Positivas:** Las tarjetas ya no dependen de la obediencia del orquestador. El texto final queda como corresponde — una introducción breve, sin viñetas ni JSON. Verificado en JVM y en imagen nativa.
* **Negativa — la que importa:** el orquestador **deja de ver los datos estructurados**. Antes recibía el JSON y podía razonar sobre él; ahora sólo llega la frase introductoria del sub-agente. Una repregunta como *«¿cuánto sale la segunda?»* ya no se responde desde la memoria conversacional: obliga a delegar de nuevo. Es un costo real, aceptado a cambio de que las tarjetas no se pierdan nunca.
* **Sobre la memoria:** por lo anterior, el historial guarda prosa sin los datos. Los turnos de seguimiento sobre entidades concretas cuestan una delegación adicional.
* **`recordArtifacts` no deduplica**, a diferencia del resto del tracker: dos sub-agentes pueden aportar legítimamente dos artifacts en un mismo turno.
* **Sigue abierta** la evolución que ya proponía [ADR-020](#adr-020-generative-ui-híbrido-con-extracción-y-validación-en-el-servidor): construir el artifact desde la salida **cruda** de la tool MCP, interceptada en `TrackingToolCallback`. Esto es un paso en esa dirección, no su llegada — el dato todavía pasa por el sub-agente, que puede transcribir mal un `id` o un `price`.
