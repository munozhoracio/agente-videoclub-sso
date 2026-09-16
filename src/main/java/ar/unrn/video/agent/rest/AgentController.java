package ar.unrn.video.agent.rest;

import ar.unrn.video.agent.generativeui.UiArtifact;
import ar.unrn.video.agent.mcp.McpKnowledgeService;
import ar.unrn.video.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final AgentService agentService;
    private final McpKnowledgeService mcpKnowledgeService;

    public AgentController(final AgentService agentService, final McpKnowledgeService mcpKnowledgeService) {
        this.agentService = agentService;
        this.mcpKnowledgeService = mcpKnowledgeService;
    }

    public record ChatRequest(String prompt, String conversationId) {}
    public record ChatResponse(
            String prompt,
            String conversationId,
            String response,
            List<String> agentsInvoked,
            List<String> toolsExecuted,
            List<String> toolsDenied,
            List<String> toolsAvailable,
            boolean fromMemory,
            List<UiArtifact> artifacts) {}

    @PostMapping(value = "/chat", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> chat(@RequestBody(required = false) final ChatRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Bad Request",
                    "message", "El campo 'prompt' es requerido en el cuerpo JSON: { \"prompt\": \"tu consulta\" }"
            ));
        }

        try {
            final AgentService.ChatResult result = agentService.chat(request.prompt(), request.conversationId());
            return ResponseEntity.ok(new ChatResponse(
                    request.prompt(),
                    result.conversationId(),
                    result.response(),
                    result.agentsInvoked(),
                    result.toolsExecuted(),
                    result.toolsDenied(),
                    result.toolsAvailable(),
                    result.fromMemory(),
                    result.artifacts()
            ));
        } catch (IllegalStateException e) {
            log.error("Domain tool or authentication state error in chat: {}", e.getMessage());
            final boolean isAuthError = e.getMessage() != null && e.getMessage().toLowerCase().contains("jwt");
            final int status = isAuthError ? 401 : 503;
            return ResponseEntity.status(status).body(Map.of(
                    "error", isAuthError ? "Unauthorized" : "Service Unavailable",
                    "message", e.getMessage() != null ? e.getMessage() : "Error en el estado del agente"
            ));
        } catch (Exception e) {
            log.error("Error executing multi-agent chat: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Agent Execution Error",
                    "message", "Error procesando la consulta con el asistente inteligente."
            ));
        }
    }

    @DeleteMapping("/chat/memory")
    public ResponseEntity<Map<String, String>> clearMemory(
            @RequestParam(required = false) final String conversationId) {
        agentService.clearMemory(conversationId);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "message", "Memoria de conversación reiniciada correctamente."
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(final HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", "El cuerpo de la petición JSON está vacío o mal formado."
        ));
    }

    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> listTools() {
        final List<String> tools = agentService.getAvailableToolNames();
        return ResponseEntity.ok(Map.of(
                "count", tools.size(),
                "tools", tools
        ));
    }

    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> listResources() {
        try {
            final List<Map<String, Object>> resources = mcpKnowledgeService.listResources();
            final List<Map<String, Object>> resourceTemplates = mcpKnowledgeService.listResourceTemplates();
            return ResponseEntity.ok(Map.of(
                    "resources", resources,
                    "resourceTemplates", resourceTemplates
            ));
        } catch (Exception e) {
            log.error("Error listando MCP resources: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "MCP Resources Error",
                    "message", "No se pudieron listar los recursos MCP de los servicios backend."
            ));
        }
    }

    @GetMapping("/prompts")
    public ResponseEntity<Map<String, Object>> listPrompts() {
        try {
            final List<Map<String, Object>> prompts = mcpKnowledgeService.listPrompts();
            return ResponseEntity.ok(Map.of(
                    "count", prompts.size(),
                    "prompts", prompts
            ));
        } catch (Exception e) {
            log.error("Error listando MCP prompts: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "MCP Prompts Error",
                    "message", "No se pudieron listar los prompts MCP de los servicios backend."
            ));
        }
    }

    @GetMapping("/resources/content")
    public ResponseEntity<Map<String, Object>> readResourceContent(@RequestParam final String uri) {
        try {
            final String content = mcpKnowledgeService.readResource(uri);
            return ResponseEntity.ok(Map.of(
                    "uri", uri,
                    "content", content
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Bad Request",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error leyendo el recurso MCP '{}': {}", uri, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "MCP Resource Read Error",
                    "message", "No se pudo leer el recurso MCP solicitado."
            ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "agent", "videoclub-agent"));
    }
}
