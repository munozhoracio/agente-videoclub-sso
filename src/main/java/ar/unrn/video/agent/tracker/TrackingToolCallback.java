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
        return delegate.call(toolInput);
    }

    @Override
    public String call(final String toolInput, final ToolContext toolContext) {
        final String toolName = delegate.getToolDefinition().name();
        tracker.recordTool(toolName);
        return delegate.call(toolInput, toolContext);
    }
}
