package ar.unrn.video.agent.orchestrator;

import ar.unrn.video.agent.subagents.CatalogSubAgent;
import ar.unrn.video.agent.subagents.MembershipSubAgent;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tools available to the Supervisor/Orchestrator Agent.
 * Instead of low-level database or MCP calls, the orchestrator delegates to specialized sub-agents.
 */
public class OrchestratorTools {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorTools.class);

    private final CatalogSubAgent catalogSubAgent;
    private final MembershipSubAgent membershipSubAgent;
    private final ExecutionTracker tracker;
    private final String callerName;

    public OrchestratorTools(
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final ExecutionTracker tracker,
            final String callerName) {
        this.catalogSubAgent = catalogSubAgent;
        this.membershipSubAgent = membershipSubAgent;
        this.tracker = tracker;
        this.callerName = callerName;
    }

    @Tool(description = "Delegates inquiries about the movie catalog, film genres, movie search, availability, stock or titles to the specialized Catalog Agent.")
    public String consultCatalogAgent(
            @ToolParam(description = "Self-contained query about movies or catalog, explicitly resolving any pronouns, anaphora or prior conversational references") final String query) {
        log.info("Orchestrator delegating to CatalogSubAgent with query: {}", query);
        return catalogSubAgent.execute(query, tracker, callerName);
    }

    @Tool(description = "Delegates inquiries about club members, socios, partners, membership status, or member listings to the specialized Membership Agent.")
    public String consultMembershipAgent(
            @ToolParam(description = "Self-contained query about members or socios, explicitly resolving any pronouns, anaphora or prior conversational references") final String query) {
        log.info("Orchestrator delegating to MembershipSubAgent with query: {}", query);
        return membershipSubAgent.execute(query, tracker, callerName);
    }
}
