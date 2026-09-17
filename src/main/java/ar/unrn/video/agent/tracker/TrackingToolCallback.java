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
    private final org.springframework.security.core.context.SecurityContext securityContext;

    public TrackingToolCallback(final ToolCallback delegate, final ExecutionTracker tracker) {
        this(delegate, tracker, org.springframework.security.core.context.SecurityContextHolder.getContext());
    }

    public TrackingToolCallback(final ToolCallback delegate, final ExecutionTracker tracker, final org.springframework.security.core.context.SecurityContext securityContext) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.securityContext = securityContext != null ? securityContext : org.springframework.security.core.context.SecurityContextHolder.getContext();
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
        final org.springframework.security.core.context.SecurityContext previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            if (securityContext != null) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
            }
            final String toolName = delegate.getToolDefinition().name();
            tracker.recordTool(toolName);
            return delegate.call(toolInput);
        } catch (Exception e) {
            checkAndRecordDenial(delegate.getToolDefinition().name(), e);
            throw e;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    @Override
    public String call(final String toolInput, final ToolContext toolContext) {
        final org.springframework.security.core.context.SecurityContext previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            if (securityContext != null) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
            }
            final String toolName = delegate.getToolDefinition().name();
            tracker.recordTool(toolName);
            return delegate.call(toolInput, toolContext);
        } catch (Exception e) {
            checkAndRecordDenial(delegate.getToolDefinition().name(), e);
            throw e;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
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
