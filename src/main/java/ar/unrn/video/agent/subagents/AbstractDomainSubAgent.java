package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.tracker.ExecutionTracker;
import ar.unrn.video.agent.tracker.TrackingToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.Set;

/**
 * Base abstract class for specialized Domain Sub-Agents.
 * Encapsulates:
 * 1. Safe tool filtering and wrapping with ExecutionTracker.
 * 2. Fail-fast validation ensuring domain tools are present to prevent hallucination.
 * 3. ChatClient prompt creation and execution.
 */
public abstract class AbstractDomainSubAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String agentName;
    private final String domainDisplayName;
    private final Set<String> allowedToolNames;
    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    protected AbstractDomainSubAgent(
            final String agentName,
            final String domainDisplayName,
            final Set<String> allowedToolNames,
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.agentName = agentName;
        this.domainDisplayName = domainDisplayName;
        this.allowedToolNames = allowedToolNames;
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public String execute(final String query, final ExecutionTracker tracker, final String callerName) {
        tracker.recordAgent(agentName);
        log.info("{} executing query for {}: {}", agentName, callerName, query);

        final ToolCallback[] availableCallbacks = toolCallbackProvider.getToolCallbacks();
        final ToolCallback[] trackingCallbacks = Arrays.stream(availableCallbacks)
                .filter(cb -> allowedToolNames.contains(cb.getToolDefinition().name()))
                .map(cb -> (ToolCallback) new TrackingToolCallback(cb, tracker))
                .toArray(ToolCallback[]::new);

        // Fail-fast: Prevent silent degradation and hallucinations if MCP tools are missing or not discovered
        if (trackingCallbacks.length == 0) {
            final String errorMsg = String.format(
                    "Falla crítica en %s: no se encontraron herramientas MCP para el dominio '%s' (esperadas: %s, disponibles en MCP: %s). "
                    + "Se interrumpe la ejecución para evitar alucinaciones.",
                    agentName,
                    domainDisplayName,
                    allowedToolNames,
                    Arrays.stream(availableCallbacks).map(c -> c.getToolDefinition().name()).toList()
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        final String systemPrompt = buildSystemPrompt(callerName);

        return chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools((Object[]) trackingCallbacks)
                .user(query)
                .call()
                .content();
    }

    /**
     * Builds domain-specific system prompt for this sub-agent.
     */
    protected abstract String buildSystemPrompt(String callerName);

    public String getAgentName() {
        return agentName;
    }

    public Set<String> getAllowedToolNames() {
        return allowedToolNames;
    }
}
