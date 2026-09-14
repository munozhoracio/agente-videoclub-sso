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
 * Specialized Sub-Agent responsible for customer membership, partners, and account records.
 * Connects exclusively to partner-related MCP tools (get_socio, list_socios).
 */
@Component
public class MembershipSubAgent {

    private static final Logger log = LoggerFactory.getLogger(MembershipSubAgent.class);

    private static final Set<String> MEMBERSHIP_TOOL_NAMES = Set.of(
            "get_socio",
            "list_socios"
    );

    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public MembershipSubAgent(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public String execute(final String query, final ExecutionTracker tracker, final String callerName) {
        tracker.recordAgent("MembershipSubAgent");
        log.info("MembershipSubAgent executing query for {}: {}", callerName, query);

        final ToolCallback[] availableCallbacks = toolCallbackProvider.getToolCallbacks();
        final ToolCallback[] trackingCallbacks = Arrays.stream(availableCallbacks)
                .filter(cb -> MEMBERSHIP_TOOL_NAMES.contains(cb.getToolDefinition().name()))
                .map(cb -> (ToolCallback) new TrackingToolCallback(cb, tracker))
                .toArray(ToolCallback[]::new);

        final String systemPrompt = String.format(
                "Sos el Sub-Agente Especialista en Socios y Membresías de VideoClub UNRN. "
                + "Atendés a %s. "
                + "Tenés acceso a herramientas MCP para consultar los datos y padrón de socios (get_socio, list_socios). "
                + "Invocá SIEMPRE las herramientas MCP disponibles para obtener la información solicitada. "
                + "No supongas permisos de antemano: ejecutá la herramienta correspondiente. "
                + "Solo si la ejecución de la herramienta falla o devuelve error de autorización/acceso denegado, explicáselo amablemente al usuario. "
                + "Respondé de forma clara, profesional y en español.",
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
