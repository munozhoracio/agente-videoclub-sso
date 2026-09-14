package ar.unrn.video.agent.tracker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe execution tracker that records which specialized sub-agents were invoked
 * and which MCP tools were executed during a single user turn.
 */
public class ExecutionTracker {

    private final List<String> agentsInvoked = new CopyOnWriteArrayList<>();
    private final List<String> toolsExecuted = new CopyOnWriteArrayList<>();
    private final List<String> toolsDenied = new CopyOnWriteArrayList<>();

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

    public List<String> getAgentsInvoked() {
        return List.copyOf(agentsInvoked);
    }

    public List<String> getToolsExecuted() {
        return List.copyOf(toolsExecuted);
    }

    public List<String> getToolsDenied() {
        return List.copyOf(toolsDenied);
    }
}
