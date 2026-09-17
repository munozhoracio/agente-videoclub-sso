package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.tracker.ExecutionTracker;
import ar.unrn.video.agent.tracker.TrackingToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;

/**
 * Base abstract class for specialized Domain Sub-Agents.
 * Encapsulates:
 * 1. Tool resolution and wrapping with ExecutionTracker.
 * 2. Fail-fast validation ensuring domain tools are present to prevent hallucination.
 * 3. ChatClient prompt creation and execution.
 *
 * <p>The domain boundary lives in configuration, not in a name list here: each subclass injects
 * a dedicated, qualified {@link SyncMcpToolCallbackProvider} (see {@code McpClientConfiguration})
 * bound to exactly one domain's MCP client. A sub-agent therefore exposes to the model every tool
 * its dedicated provider returns, whatever that set currently is. Authorization is unaffected by
 * this: every tool call still carries the caller's JWT and is enforced server-side via
 * {@code @PreAuthorize}, so this class never makes an authorization decision. The accepted
 * tradeoff is that a tool added to a domain's MCP server reaches this sub-agent's model
 * automatically, with no change required here.
 */
public abstract class AbstractDomainSubAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String agentName;
    private final String domainDisplayName;
    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    protected AbstractDomainSubAgent(
            final String agentName,
            final String domainDisplayName,
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.agentName = agentName;
        this.domainDisplayName = domainDisplayName;
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public String execute(final String query, final ExecutionTracker tracker, final String callerName) {
        tracker.recordAgent(agentName);
        log.info("{} executing query for {}: {}", agentName, callerName, query);

        final ToolCallback[] trackingCallbacks = resolveDomainTools(tracker);

        final String systemPrompt = buildSystemPrompt(callerName);

        return chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools((Object[]) trackingCallbacks)
                .user(query)
                .call()
                .content();
    }

    /**
     * Resolves every tool callback exposed by this agent's dedicated MCP provider, wrapping each
     * one in a {@link TrackingToolCallback} bound to the given {@link ExecutionTracker}.
     *
     * <p>Fails fast with {@link IllegalStateException} when the provider exposes zero callbacks
     * (its MCP server is down or discovery failed), so execution stops here instead of letting
     * the model answer without tools and invent data.
     */
    protected ToolCallback[] resolveDomainTools(final ExecutionTracker tracker) {
        final ToolCallback[] availableCallbacks = toolCallbackProvider.getToolCallbacks();
        final ToolCallback[] trackingCallbacks = Arrays.stream(availableCallbacks)
                .map(cb -> (ToolCallback) new TrackingToolCallback(cb, tracker))
                .toArray(ToolCallback[]::new);

        // Fail-fast: Prevent silent degradation and hallucinations if MCP tools are missing or not discovered
        if (trackingCallbacks.length == 0) {
            final String errorMsg = String.format(
                    "Falla crítica en %s: el servidor MCP del dominio '%s' no expuso ninguna herramienta. "
                    + "Se interrumpe la ejecución para evitar alucinaciones.",
                    agentName,
                    domainDisplayName
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        return trackingCallbacks;
    }

    /**
     * Builds domain-specific system prompt for this sub-agent.
     */
    protected abstract String buildSystemPrompt(String callerName);

    public String getAgentName() {
        return agentName;
    }
}
