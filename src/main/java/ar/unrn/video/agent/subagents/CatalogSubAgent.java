package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.tracker.ExecutionTracker;
import ar.unrn.video.agent.tracker.TrackingToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Specialized Sub-Agent responsible for the movie catalog domain.
 * Connects exclusively to catalog-related MCP tools (list_movies, get_movie, search_movies).
 */
@Component
public class CatalogSubAgent {

    private static final Logger log = LoggerFactory.getLogger(CatalogSubAgent.class);

    private static final Set<String> CATALOG_TOOL_NAMES = Set.of(
            "list_movies",
            "get_movie",
            "search_movies"
    );

    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public CatalogSubAgent(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public String execute(final String query, final ExecutionTracker tracker, final String callerName) {
        tracker.recordAgent("CatalogSubAgent");
        log.info("CatalogSubAgent executing query for {}: {}", callerName, query);

        final ToolCallback[] availableCallbacks = toolCallbackProvider.getToolCallbacks();
        final ToolCallback[] trackingCallbacks = Arrays.stream(availableCallbacks)
                .filter(cb -> CATALOG_TOOL_NAMES.contains(cb.getToolDefinition().name()))
                .map(cb -> (ToolCallback) new TrackingToolCallback(cb, tracker))
                .toArray(ToolCallback[]::new);

        final String systemPrompt = String.format(
                "Sos el Sub-Agente Especialista en Catálogo de Películas de VideoClub UNRN. "
                + "Atendés consultas de %s sobre películas, estrenos, géneros, actores y disponibilidad en el catálogo. "
                + "Tenés acceso EXCLUSIVO a las herramientas del catálogo de películas. "
                + "Utilizá SIEMPRE las herramientas MCP disponibles para consultar datos reales. "
                + "Respondé de forma clara, concisa y en español.",
                callerName != null ? callerName : "Usuario"
        );

        var promptSpec = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .user(query);

        if (trackingCallbacks.length > 0) {
            promptSpec = promptSpec.tools((Object[]) trackingCallbacks);
        }

        return promptSpec.call().content();
    }
}
