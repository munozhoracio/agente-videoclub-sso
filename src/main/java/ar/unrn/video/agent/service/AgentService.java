package ar.unrn.video.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient chatClient;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;

    public AgentService(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;

        final ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        log.info("Registered {} MCP tool callback(s) into Agent ChatClient: {}",
                toolCallbacks.length,
                Arrays.stream(toolCallbacks).map(t -> t.getToolDefinition().name()).toList());

        this.chatClient = chatClientBuilder
                .defaultSystem("Sos el asistente de inteligencia artificial oficial de VideoClub UNRN. "
                        + "Tenés acceso a herramientas MCP para consultar el catálogo de películas y el padrón de socios. "
                        + "Utilizá siempre las herramientas disponibles cuando el usuario pregunte por películas, socios o datos del sistema. "
                        + "Respondé siempre en español de forma clara, precisa y concisa.")
                .defaultTools((Object[]) toolCallbacks)
                .build();
    }

    /**
     * Executes the conversational agent with the user prompt, automatically calling
     * any necessary MCP tools.
     */
    public String chat(final String userPrompt) {
        log.info("Agent received prompt: {}", userPrompt);

        final String response = chatClient.prompt()
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
