package ar.unrn.video.agent.orchestrator;

import ar.unrn.video.agent.generativeui.GenerativeUiExtractor;
import ar.unrn.video.agent.subagents.CatalogSubAgent;
import ar.unrn.video.agent.subagents.MembershipSubAgent;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ar.unrn.video.agent.model.AgentStreamEvent;

import java.util.function.Consumer;

/**
 * Tools available to the Supervisor/Orchestrator Agent.
 * Instead of low-level database or MCP calls, the orchestrator delegates to specialized sub-agents.
 *
 * <h2>Where Generative UI artifacts are captured</h2>
 *
 * <p>A sub-agent answers with prose plus a fenced structured block. That block used to travel
 * through the orchestrator's reply and get extracted from the final text. It does not any more,
 * because the orchestrator is a language model whose whole job is to rewrite prose: asked to
 * consolidate an answer, it reliably turned the block into markdown bullets and the artifact was
 * gone. No prompt rule prevented it; "PRESERVE this block verbatim" is a request, not a guarantee.
 *
 * <p>So the block is lifted here, the moment the sub-agent returns and before the orchestrator ever
 * sees it. The orchestrator receives only the stripped prose — it cannot preserve, mangle or leak
 * what it was never given — while the artifact goes straight to the {@link ExecutionTracker} that
 * already carries the turn's other structured facts.
 */
public class OrchestratorTools {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorTools.class);

    private final CatalogSubAgent catalogSubAgent;
    private final MembershipSubAgent membershipSubAgent;
    private final ExecutionTracker tracker;
    private final GenerativeUiExtractor generativeUiExtractor;
    private final String callerName;
    private final Consumer<AgentStreamEvent> eventConsumer;
    private final org.springframework.security.core.context.SecurityContext securityContext;

    public OrchestratorTools(
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final ExecutionTracker tracker,
            final GenerativeUiExtractor generativeUiExtractor,
            final String callerName) {
        this(catalogSubAgent, membershipSubAgent, tracker, generativeUiExtractor, callerName, null, org.springframework.security.core.context.SecurityContextHolder.getContext());
    }

    public OrchestratorTools(
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final ExecutionTracker tracker,
            final GenerativeUiExtractor generativeUiExtractor,
            final String callerName,
            final Consumer<AgentStreamEvent> eventConsumer) {
        this(catalogSubAgent, membershipSubAgent, tracker, generativeUiExtractor, callerName, eventConsumer, org.springframework.security.core.context.SecurityContextHolder.getContext());
    }

    public OrchestratorTools(
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final ExecutionTracker tracker,
            final GenerativeUiExtractor generativeUiExtractor,
            final String callerName,
            final Consumer<AgentStreamEvent> eventConsumer,
            final org.springframework.security.core.context.SecurityContext securityContext) {
        this.catalogSubAgent = catalogSubAgent;
        this.membershipSubAgent = membershipSubAgent;
        this.tracker = tracker;
        this.generativeUiExtractor = generativeUiExtractor;
        this.callerName = callerName;
        this.eventConsumer = eventConsumer;
        this.securityContext = securityContext != null ? securityContext : org.springframework.security.core.context.SecurityContextHolder.getContext();
    }

    @Tool(description = "Delegates any operation related to the movie catalog to the specialized Catalog Agent. "
            + "Use this for: searching movies, listing the catalog, checking genres, availability or stock, "
            + "AND ALSO for creating, registering or adding new movies to the catalog. "
            + "If the user wants to create or add a movie, always delegate here.")
    public String consultCatalogAgent(
            @ToolParam(description = "Self-contained query or instruction about movies or catalog, explicitly resolving any pronouns, anaphora or prior conversational references") final String query) {
        log.info("Orchestrator delegating to CatalogSubAgent with query: {}", query);
        if (eventConsumer != null) {
            eventConsumer.accept(AgentStreamEvent.status("CatalogSubAgent", "Consultando catálogo de películas..."));
        }
        final org.springframework.security.core.context.SecurityContext previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            if (securityContext != null) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
            }
            final String result = captureArtifacts("CatalogSubAgent", catalogSubAgent.execute(query, tracker, callerName));
            if (eventConsumer != null) {
                eventConsumer.accept(AgentStreamEvent.status("CatalogSubAgent", "Respuesta recibida del catálogo"));
            }
            return result;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    @Tool(description = "Delegates inquiries about club members, socios, partners, membership status, or member listings to the specialized Membership Agent.")
    public String consultMembershipAgent(
            @ToolParam(description = "Self-contained query about members or socios, explicitly resolving any pronouns, anaphora or prior conversational references") final String query) {
        log.info("Orchestrator delegating to MembershipSubAgent with query: {}", query);
        if (eventConsumer != null) {
            eventConsumer.accept(AgentStreamEvent.status("MembershipSubAgent", "Consultando padrón de socios..."));
        }
        final org.springframework.security.core.context.SecurityContext previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            if (securityContext != null) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
            }
            final String result = captureArtifacts("MembershipSubAgent", membershipSubAgent.execute(query, tracker, callerName));
            if (eventConsumer != null) {
                eventConsumer.accept(AgentStreamEvent.status("MembershipSubAgent", "Respuesta recibida de socios"));
            }
            return result;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * Records whatever structured blocks the sub-agent emitted and hands back only the prose.
     *
     * @return the sub-agent's text with every recognized fence removed; this is what the
     *         orchestrator reads, so the structured data is no longer at its mercy
     */
    private String captureArtifacts(final String agentName, final String subAgentResponse) {
        final GenerativeUiExtractor.ExtractionResult extraction =
                generativeUiExtractor.extract(subAgentResponse);

        if (!extraction.artifacts().isEmpty()) {
            tracker.recordArtifacts(extraction.artifacts());
            log.info("Captured {} Generative UI artifact(s) from {} before the orchestrator saw the text",
                    extraction.artifacts().size(), agentName);
            if (eventConsumer != null) {
                eventConsumer.accept(AgentStreamEvent.artifact(extraction.artifacts()));
            }
        }

        final String text = extraction.text() != null ? extraction.text().trim() : "";
        if (text.isBlank()) {
            if (!extraction.artifacts().isEmpty()) {
                return "Se obtuvieron y cargaron exitosamente las películas en las tarjetas interactivas del sistema. "
                        + "Confirmale amablemente al usuario sin repetir los detalles técnicos ni bloques JSON.";
            }
            return "Operación completada por " + agentName + ".";
        }

        return text;
    }
}
