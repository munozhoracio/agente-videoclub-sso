package ar.unrn.video.agent.tracker;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator that intercepts tool invocations to record executed tool names into an {@link ExecutionTracker}.
 */
public class TrackingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ExecutionTracker tracker;

    public TrackingToolCallback(final ToolCallback delegate, final ExecutionTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
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
        tracker.recordTool(toolName);
        try {
            return delegate.call(toolInput);
        } catch (Exception e) {
            checkAndRecordDenial(toolName, e);
            throw e;
        }
    }

    @Override
    public String call(final String toolInput, final ToolContext toolContext) {
        final String toolName = delegate.getToolDefinition().name();
        tracker.recordTool(toolName);
        try {
            return delegate.call(toolInput, toolContext);
        } catch (Exception e) {
            checkAndRecordDenial(toolName, e);
            throw e;
        }
    }

    private void checkAndRecordDenial(final String toolName, final Throwable t) {
        Throwable current = t;
        while (current != null) {
            final String msg = current.getMessage();
            if (msg != null && (
                    msg.contains("Access Denied")
                    || msg.contains("AccessDeniedException")
                    || msg.contains("403")
                    || msg.contains("Forbidden")
                    || msg.toLowerCase().contains("denied")
                    || msg.toLowerCase().contains("permis")
            )) {
                tracker.recordToolDenied(toolName);
                return;
            }
            current = current.getCause();
        }
    }
}
