package ar.unrn.video.agent.subagents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Specialized Sub-Agent responsible for the movie catalog domain.
 * Connects exclusively to catalog-related MCP tools (list_movies, get_movie, search_movies).
 */
@Component
public class CatalogSubAgent extends AbstractDomainSubAgent {

    public static final Set<String> CATALOG_TOOL_NAMES = Set.of(
            "list_movies",
            "get_movie",
            "search_movies"
    );

    public CatalogSubAgent(
            final ChatClient.Builder chatClientBuilder,
            final SyncMcpToolCallbackProvider toolCallbackProvider) {
        super("CatalogSubAgent", "Catálogo de Películas", CATALOG_TOOL_NAMES, chatClientBuilder, toolCallbackProvider);
    }

    @Override
    protected String buildSystemPrompt(final String callerName) {
        return String.format(
                "Sos el Sub-Agente Especialista en Catálogo de Películas de VideoClub UNRN. "
                + "Atendés consultas de %s sobre películas, estrenos, géneros, actores y disponibilidad en el catálogo. "
                + "Tenés acceso EXCLUSIVO a las herramientas del catálogo de películas. "
                + "Utilizá SIEMPRE las herramientas MCP disponibles para consultar datos reales. "
                + "Respondé de forma clara, concisa y en español.",
                callerName != null ? callerName : "Usuario"
        );
    }
}
