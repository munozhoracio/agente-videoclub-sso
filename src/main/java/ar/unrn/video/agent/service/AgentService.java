package ar.unrn.video.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public AgentService(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public record ChatResult(String response, List<String> toolsExecuted, List<String> toolsAvailable) {}

    /**
     * Executes conversational agent with user prompt, injecting tools dynamically
     * and tracking which tools were executed during this turn.
     */
    public ChatResult chat(final String userPrompt) {
        log.info("Agent received prompt: {}", userPrompt);

        final List<String> toolsExecuted = new CopyOnWriteArrayList<>();
        final ToolCallback[] availableCallbacks = toolCallbackProvider.getToolCallbacks();
        final List<String> availableToolNames = Arrays.stream(availableCallbacks)
                .map(t -> t.getToolDefinition().name())
                .toList();

        final ToolCallback[] trackingCallbacks = Arrays.stream(availableCallbacks)
                .map(cb -> new TrackingToolCallback(cb, toolsExecuted))
                .toArray(ToolCallback[]::new);

        String callerName = "Usuario";
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            final String name = jwtAuth.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                callerName = name;
            } else {
                final String pref = jwtAuth.getToken().getClaimAsString("preferred_username");
                if (pref != null && !pref.isBlank()) {
                    callerName = pref;
                }
            }
        }

        final String systemPrompt = String.format(
                "Sos el asistente de inteligencia artificial oficial de VideoClub UNRN. "
                + "Estás atendiendo a %s. "
                + "Tenés acceso a herramientas MCP para consultar el catálogo de películas y el padrón de socios. "
                + "Utilizá siempre las herramientas disponibles cuando se pregunte por películas, socios o datos del sistema. "
                + "Si una herramienta devuelve un error de permisos o acceso denegado, explicáselo amablemente al usuario. "
                + "Respondé siempre en español de forma clara, precisa y concisa.",
                callerName
        );

        final String response = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools((Object[]) trackingCallbacks)
                .user(userPrompt)
                .call()
                .content();

        log.info("Agent response generated. Tools executed: {}", toolsExecuted);
        return new ChatResult(response, toolsExecuted, availableToolNames);
    }

    /**
     * Returns the names of all currently discovered MCP tools.
     */
    public List<String> getAvailableToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    /**
     * Decorator that intercepts tool invocations to track executed tool names.
     */
    private static class TrackingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final List<String> executedTools;

        TrackingToolCallback(final ToolCallback delegate, final List<String> executedTools) {
            this.delegate = delegate;
            this.executedTools = executedTools;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(final String toolInput) {
            final String toolName = delegate.getToolDefinition().name();
            if (!executedTools.contains(toolName)) {
                executedTools.add(toolName);
            }
            return delegate.call(toolInput);
        }

        @Override
        public String call(final String toolInput, final ToolContext toolContext) {
            final String toolName = delegate.getToolDefinition().name();
            if (!executedTools.contains(toolName)) {
                executedTools.add(toolName);
            }
            return delegate.call(toolInput, toolContext);
        }
    }
}
