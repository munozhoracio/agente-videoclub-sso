package ar.unrn.video.agent.service;

import ar.unrn.video.agent.auth.UserProfile;
import ar.unrn.video.agent.orchestrator.OrchestratorTools;
import ar.unrn.video.agent.subagents.CatalogSubAgent;
import ar.unrn.video.agent.subagents.MembershipSubAgent;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Orchestrator / Supervisor Agent Service with Conversational Memory for VideoClub UNRN.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final CatalogSubAgent catalogSubAgent;
    private final MembershipSubAgent membershipSubAgent;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    private final ChatMemory chatMemory;

    public AgentService(
            final ChatClient.Builder chatClientBuilder,
            final CatalogSubAgent catalogSubAgent,
            final MembershipSubAgent membershipSubAgent,
            final SyncMcpToolCallbackProvider toolCallbackProvider,
            final ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.catalogSubAgent = catalogSubAgent;
        this.membershipSubAgent = membershipSubAgent;
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatMemory = chatMemory;
    }

    public record ChatResult(
            String response,
            String conversationId,
            List<String> agentsInvoked,
            List<String> toolsExecuted,
            List<String> toolsAvailable
    ) {}

    /**
     * Orchestrates user chat using conversational memory and specialized sub-agents.
     */
    public ChatResult chat(final String userPrompt, final String requestedConversationId) {
        log.info("Orchestrator received prompt: {}", userPrompt);

        final UserProfile user = UserProfile.from(SecurityContextHolder.getContext().getAuthentication());
        final String conversationId = (requestedConversationId != null && !requestedConversationId.isBlank())
                ? requestedConversationId
                : user.username();

        // If this conversation memory is brand new, seed it with the user's presentation
        seedInitialMemoryIfEmpty(conversationId, user);

        final ExecutionTracker tracker = new ExecutionTracker();
        final OrchestratorTools orchestratorTools = new OrchestratorTools(
                catalogSubAgent,
                membershipSubAgent,
                tracker,
                user.fullName()
        );

        final String systemPrompt = String.format(
                "Sos el Agente Orquestador y Supervisor oficial de VideoClub UNRN. "
                + "Estás atendiendo a %s (usuario: '%s', email: '%s', rol: %s). "
                + "Mantené y aprovechá el contexto y la memoria de la conversación a lo largo de los turnos. "
                + "Tu función es coordinar la atención al usuario delegando en tus sub-agentes especializados: "
                + "1. Especialista en Catálogo (consultCatalogAgent): Para consultas sobre películas, géneros, estrenos, stock o disponibilidad en el catálogo. "
                + "2. Especialista en Membresías y Socios (consultMembershipAgent): Para consultas sobre datos de socios, estado de clientes o padrón de membresías. "
                + "Reglas de comportamiento: "
                + "- Recordá y utilizá las respuestas anteriores de esta conversación para responder de forma coherente. "
                + "- Para saludos de cortesía, presentaciones o preguntas generales sobre qué podés hacer, respondé directamente con amabilidad sin invocar a ningún sub-agente. "
                + "- Si la consulta involucra películas o catálogo, delegá inmediatamente en consultCatalogAgent. "
                + "- Si la consulta involucra socios o membresías, delegá inmediatamente en consultMembershipAgent. "
                + "- Si una consulta requiere ambos dominios, podés invocar a ambos sub-agentes y consolidar una respuesta integrada. "
                + "- Respondé siempre en español de forma clara, natural, profesional y precisa.",
                user.fullName(), user.username(), user.email(), user.roles()
        );

        final MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        final String response = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools(orchestratorTools)
                .user(userPrompt)
                .advisors(advisorSpec -> {
                    advisorSpec.advisors(memoryAdvisor);
                    advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId);
                })
                .call()
                .content();

        final List<String> agentsInvoked = tracker.getAgentsInvoked();
        final List<String> toolsExecuted = tracker.getToolsExecuted();
        final List<String> toolsAvailable = getAvailableToolNames();

        log.info("Turn completed for conversationId: {}. Agents: {}, Tools: {}",
                conversationId, agentsInvoked, toolsExecuted);

        return new ChatResult(response, conversationId, agentsInvoked, toolsExecuted, toolsAvailable);
    }

    /**
     * Clears the conversational memory for a given conversation ID.
     */
    public void clearMemory(final String conversationId) {
        final UserProfile user = UserProfile.from(SecurityContextHolder.getContext().getAuthentication());
        final String effectiveId = (conversationId != null && !conversationId.isBlank())
                ? conversationId
                : user.username();
        chatMemory.clear(effectiveId);
        log.info("Cleared conversation memory for: {}", effectiveId);
    }

    /**
     * Seeds initial conversational memory with user presentation if this session has no history yet.
     */
    private void seedInitialMemoryIfEmpty(final String conversationId, final UserProfile user) {
        if (chatMemory.get(conversationId).isEmpty()) {
            log.info("Seeding initial memory for conversation {} with user profile {}", conversationId, user.username());
            final String userPresentation = String.format(
                    "Hola, me presento: mi nombre es %s (usuario: '%s', email: '%s', rol: %s).",
                    user.fullName(), user.username(), user.email(), user.roles()
            );
            final String assistantAck = String.format(
                    "¡Hola %s! Un placer saludarte. Ya tengo presentes tus datos y tu rol (%s) en el VideoClub UNRN. ¿En qué te puedo ayudar hoy?",
                    user.fullName(), String.join(", ", user.roles())
            );
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userPresentation),
                    new AssistantMessage(assistantAck)
            ));
        }
    }

    /**
     * Returns the names of all currently discovered MCP tools in the system.
     */
    public List<String> getAvailableToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name())
                .toList();
    }
}
