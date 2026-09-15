package ar.unrn.video.agent.tracker;

import ar.unrn.video.agent.generativeui.UiArtifact;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe execution tracker that records which specialized sub-agents were invoked,
 * which MCP tools were executed, and which Generative UI artifacts were produced during a
 * single user turn.
 *
 * <p>Artifacts are recorded here, at the moment a sub-agent returns, rather than read back
 * out of the orchestrator's final text. The orchestrator is a language model asked to
 * consolidate prose; it was observed rewriting a sub-agent's structured block into markdown
 * bullets, which silently destroyed the artifact. Prose is its job, so the structured data
 * is taken off that path entirely and travels here instead.
 */
public class ExecutionTracker {

    private final List<String> agentsInvoked = new CopyOnWriteArrayList<>();
    private final List<String> toolsExecuted = new CopyOnWriteArrayList<>();
    private final List<String> toolsDenied = new CopyOnWriteArrayList<>();
    private final List<UiArtifact> artifacts = new CopyOnWriteArrayList<>();

    public void recordAgent(final String agentName) {
        if (agentName != null && !agentsInvoked.contains(agentName)) {
            agentsInvoked.add(agentName);
        }
    }

    public void recordTool(final String toolName) {
        if (toolName != null && !toolsExecuted.contains(toolName)) {
            toolsExecuted.add(toolName);
        }
    }

    public void recordToolDenied(final String toolName) {
        if (toolName != null && !toolsDenied.contains(toolName)) {
            toolsDenied.add(toolName);
        }
    }

    /**
     * Records artifacts in the order sub-agents produced them. Unlike the other record
     * methods there is no de-duplication: two sub-agents can legitimately contribute two
     * artifacts in one turn, and an empty list is a no-op.
     */
    public void recordArtifacts(final List<UiArtifact> produced) {
        if (produced != null && !produced.isEmpty()) {
            artifacts.addAll(produced);
        }
    }

    public List<String> getAgentsInvoked() {
        return List.copyOf(agentsInvoked);
    }

    public List<UiArtifact> getArtifacts() {
        return List.copyOf(artifacts);
    }

    public List<String> getToolsExecuted() {
        return List.copyOf(toolsExecuted);
    }

    public List<String> getToolsDenied() {
        return List.copyOf(toolsDenied);
    }
}
