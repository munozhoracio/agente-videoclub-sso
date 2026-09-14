package ar.unrn.video.agent.service;

import ar.unrn.video.agent.orchestrator.OrchestratorTools;
import ar.unrn.video.agent.subagents.CatalogSubAgent;
import ar.unrn.video.agent.subagents.MembershipSubAgent;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Orchestrator / Supervisor Agent Service for VideoClub UNRN.
 * Coordinates conversation and delegates specialized tasks to sub-agents.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final CatalogSubAgent catalogSubAgent;
    private final MembershipSubAgent membershipSubAgent;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public AgentService(
            final ChatClient.Builder chatClientBuilder,
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.catalogSubAgent = catalogSubAgent;
        this.membershipSubAgent = membershipSubAgent;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public record ChatResult(
            String response,
            List<String> agentsInvoked,
            List<String> toolsExecuted,
            List<String> toolsAvailable
    ) {}

    /**
     * Orchestrates user chat by dispatching to specialized sub-agents via tool-calling.
     */
    public ChatResult chat(final String userPrompt) {
        log.info("Orchestrator received prompt: {}", userPrompt);

        final ExecutionTracker tracker = new ExecutionTracker();
        final String callerName = extractCallerName();

        final OrchestratorTools orchestratorTools = new OrchestratorTools(
                catalogSubAgent,
                membershipSubAgent,
                tracker,
                callerName
        );

        final String systemPrompt = String.format(
                "Sos el Agente Orquestador y Supervisor oficial de VideoClub UNRN. "
                + "Estás atendiendo a %s. "
                + "Tu función principal es coordinar la atención al usuario delegando en tus sub-agentes especializados: "
                + "1. Especialista en Catálogo (consultCatalogAgent): Para consultas sobre películas, géneros, estrenos, stock o disponibilidad en el catálogo. "
                + "2. Especialista en Membresías y Socios (consultMembershipAgent): Para consultas sobre datos de socios, estado de clientes o padrón de membresías. "
                + "Reglas de comportamiento: "
                + "- Para saludos de cortesía, presentaciones o preguntas generales sobre qué podés hacer, respondé directamente con amabilidad sin invocar a ningún sub-agente. "
                + "- Si la consulta involucra películas o catálogo, delegá inmediatamente en consultCatalogAgent. "
                + "- Si la consulta involucra socios o membresías, delegá inmediatamente en consultMembershipAgent. "
                + "- Si una consulta requiere ambos dominios, podés invocar a ambos sub-agentes y consolidar una respuesta integrada. "
                + "- Respondé siempre en español de forma clara, natural, profesional y precisa.",
                callerName
        );

        final String response = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools(orchestratorTools)
                .user(userPrompt)
                .call()
                .content();

        final List<String> agentsInvoked = tracker.getAgentsInvoked();
        final List<String> toolsExecuted = tracker.getToolsExecuted();
        final List<String> toolsAvailable = getAvailableToolNames();

        log.info("Orchestration completed. Agents invoked: {}, Tools executed: {}", agentsInvoked, toolsExecuted);
        return new ChatResult(response, agentsInvoked, toolsExecuted, toolsAvailable);
    }

    /**
     * Returns the names of all currently discovered MCP tools in the system.
     */
    public List<String> getAvailableToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    private String extractCallerName() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            final String name = jwtAuth.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            final String pref = jwtAuth.getToken().getClaimAsString("preferred_username");
            if (pref != null && !pref.isBlank()) {
                return pref;
            }
        }
        return "Usuario";
    }
}
