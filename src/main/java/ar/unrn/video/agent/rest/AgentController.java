package ar.unrn.video.agent.rest;

import ar.unrn.video.agent.generativeui.UiArtifact;
import ar.unrn.video.agent.model.AgentStreamEvent;
import ar.unrn.video.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private final AgentService agentService;

    public AgentController(final AgentService agentService) {
        this.agentService = agentService;
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

    @PostMapping(value = "/chat/stream", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody(required = false) final ChatRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            final SseEmitter emitter = new SseEmitter(5_000L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(AgentStreamEvent.error("BadRequest", "El campo 'prompt' es requerido en el cuerpo JSON: { \"prompt\": \"tu consulta\" }")));
                emitter.complete();
            } catch (IOException ignored) {
            }
            return emitter;
        }

        final SecurityContext securityContext = SecurityContextHolder.getContext();
        final SseEmitter emitter = new SseEmitter(60_000L);
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> {
            completed.set(true);
            log.warn("SSE stream timed out for conversationId: {}", request.conversationId());
        });
        emitter.onError(t -> {
            completed.set(true);
            log.warn("SSE stream error for conversationId {}: {}", request.conversationId(), t.getMessage());
        });

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                agentService.chatStream(
                        request.prompt(),
                        request.conversationId(),
                        event -> {
                            if (completed.get()) {
                                return;
                            }
                            synchronized (emitter) {
                                if (!completed.get()) {
                                    try {
                                        emitter.send(SseEmitter.event()
                                                .name(event.event())
                                                .data(event));
                                    } catch (Exception e) {
                                        completed.set(true);
                                        log.warn("Cannot send SSE event (client disconnected or stream closed): {}", e.getMessage());
                                    }
                                }
                            }
                        }
                );
                if (completed.compareAndSet(false, true)) {
                    synchronized (emitter) {
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error executing multi-agent streaming chat: {}", e.getMessage(), e);
                if (completed.compareAndSet(false, true)) {
                    synchronized (emitter) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(AgentStreamEvent.error(e.getClass().getSimpleName(), e.getMessage())));
                            emitter.completeWithError(e);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        return emitter;
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

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "agent", "videoclub-agent"));
    }
}
