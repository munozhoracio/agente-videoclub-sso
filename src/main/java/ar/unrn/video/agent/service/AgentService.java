package ar.unrn.video.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public AgentService(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    /**
     * Executes conversational agent with user prompt, injecting tools dynamically
     * and enriching system prompt with caller's identity.
     */
    public String chat(final String userPrompt) {
        log.info("Agent received prompt: {}", userPrompt);

        final ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        log.debug("Active tools available for execution: {}",
                Arrays.stream(toolCallbacks).map(t -> t.getToolDefinition().name()).toList());

        String callerName = "Usuario";
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            final String name = jwtAuth.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                callerName = name;
            } else {
                final String pref = jwtAuth.getToken().getClaimAsString("preferred_username");
                if (pref != null && !pref.isBlank()) {
                    callerName = pref;
                }
            }
        }

        final String systemPrompt = String.format(
                "Sos el asistente de inteligencia artificial oficial de VideoClub UNRN. "
                + "Estás atendiendo a %s. "
                + "Tenés acceso a herramientas MCP para consultar el catálogo de películas y el padrón de socios. "
                + "Utilizá siempre las herramientas disponibles cuando se pregunte por películas, socios o datos del sistema. "
                + "Si una herramienta devuelve un error de permisos o acceso denegado, explicáselo amablemente al usuario. "
                + "Respondé siempre en español de forma clara, precisa y concisa.",
                callerName
        );

        final String response = chatClientBuilder.build().prompt()
                .system(systemPrompt)
                .tools((Object[]) toolCallbacks)
                .user(userPrompt)
                .call()
                .content();

        log.debug("Agent response: {}", response);
        return response;
    }

    /**
     * Returns the names of all currently discovered MCP tools.
     */
    public List<String> getAvailableToolNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name())
                .toList();
    }
}
