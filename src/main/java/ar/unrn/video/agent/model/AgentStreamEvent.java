package ar.unrn.video.agent.model;

import ar.unrn.video.agent.generativeui.UiArtifact;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Typed events emitted over Server-Sent Events (SSE) during an agentic chat turn.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStreamEvent(
        String event,
        String agent,
        String message,
        String text,
        List<UiArtifact> artifacts,
        String conversationId,
        List<String> agentsInvoked,
        List<String> toolsExecuted,
        List<String> toolsDenied,
        List<String> toolsAvailable,
        Boolean fromMemory,
        String error
) {
    public static AgentStreamEvent status(final String agent, final String message) {
        return new AgentStreamEvent("status", agent, message, null, null, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent delta(final String text) {
        return new AgentStreamEvent("delta", null, null, text, null, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent artifact(final List<UiArtifact> artifacts) {
        return new AgentStreamEvent("artifact", null, null, null, artifacts, null, null, null, null, null, null, null);
    }

    public static AgentStreamEvent done(
            final String conversationId,
            final List<String> agentsInvoked,
            final List<String> toolsExecuted,
            final List<String> toolsDenied,
            final boolean fromMemory) {
        return done(conversationId, agentsInvoked, toolsExecuted, toolsDenied, null, fromMemory);
    }

    public static AgentStreamEvent done(
            final String conversationId,
            final List<String> agentsInvoked,
            final List<String> toolsExecuted,
            final List<String> toolsDenied,
            final List<String> toolsAvailable,
            final boolean fromMemory) {
        return new AgentStreamEvent("done", null, null, null, null, conversationId, agentsInvoked, toolsExecuted, toolsDenied, toolsAvailable, fromMemory, null);
    }

    public static AgentStreamEvent error(final String error, final String message) {
        return new AgentStreamEvent("error", null, message, null, null, null, null, null, null, null, null, error);
    }
}
