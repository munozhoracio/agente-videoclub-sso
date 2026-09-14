package ar.unrn.video.agent.subagents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Specialized Sub-Agent responsible for the movie catalog domain.
 * Connects exclusively to catalog-related MCP tools (list_movies, get_movie, search_movies, create_movie).
 */
@Component
public class CatalogSubAgent extends AbstractDomainSubAgent {

    public static final Set<String> CATALOG_TOOL_NAMES = Set.of(
            "list_movies",
            "get_movie",
            "search_movies",
            "create_movie"
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
                + "Atendés consultas de %s sobre películas, estrenos, géneros y disponibilidad en el catálogo. "
                + "También podés CREAR nuevas películas usando la herramienta create_movie cuando el usuario lo solicite. "
                + "Tenés acceso EXCLUSIVO a las herramientas del catálogo de películas. "
                + "Utilizá SIEMPRE las herramientas MCP disponibles para consultar o registrar datos reales. "
                + "REGLA DE FORMATO GENERATIVE UI: Cuando la respuesta contenga una o más películas obtenidas del catálogo, "
                + "NUNCA detalles ni listes sus atributos en viñetas de texto (prohibido escribir listas con Título, Género, Precio o imágenes markdown). "
                + "Tu texto conversacional debe ser únicamente una breve frase amigable de introducción o cortesía (ejemplo: 'Sí, encontramos esta película en el catálogo:' o 'Te comparto las películas disponibles:'), "
                + "y SIEMPRE agregá al final el bloque JSON estricto delimitado exactamente así: "
                + "```json:movies\\n"
                + "[\\n"
                + "  {\\\"id\\\": 10029, \\\"title\\\": \\\"Nombre\\\", \\\"genre\\\": \\\"GENRE\\\", \\\"price\\\": \\\"150.00\\\", \\\"imageUrl\\\": \\\"https://...\\\"}\\n"
                + "]\\n"
                + "``` "
                + "El frontend genera automáticamente las tarjetas interactivas completas a partir de ese JSON, por lo que duplicar datos en el texto está prohibido. "
                + "Si una película no tiene imagen o precio, incluí null. "
                + "Respondé de forma clara, concisa y en español.",
                callerName != null ? callerName : "Usuario"
        );
    }
}
