package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.mcp.McpKnowledgeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final String GENRES_RESOURCE_URI = "catalog://genres";

    private final McpKnowledgeService mcpKnowledgeService;

    public CatalogSubAgent(
            final ChatClient.Builder chatClientBuilder,
            @Qualifier("catalogTools") final SyncMcpToolCallbackProvider toolCallbackProvider,
            final McpKnowledgeService mcpKnowledgeService) {
        super("CatalogSubAgent", "Catálogo de Películas", CATALOG_TOOL_NAMES, chatClientBuilder, toolCallbackProvider);
        this.mcpKnowledgeService = mcpKnowledgeService;
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
        ) + genresSection();
    }

    /**
     * Reads the {@code catalog://genres} MCP resource and renders it as a delimited addendum to
     * the system prompt, so the model has the catalog's real genre constants instead of guessing
     * a spelling (e.g. "Sci-Fi" instead of {@code SCIENCE_FICTION}) that the database's
     * {@code CHECK} constraint would then reject.
     *
     * <p>This read happens on every call, at query time, never in the constructor: the caller's
     * JWT only exists while a user request is being handled, and {@code McpClientConfiguration}
     * refuses MCP calls made outside of one.
     *
     * <p>A failed read only degrades the prompt, it does not fail the request. This is
     * deliberately different from the fail-fast in {@link AbstractDomainSubAgent}, which refuses
     * to run when no domain tools are discovered because answering without tools means the model
     * invents data outright. Losing the genre hint does not create that risk: the model still has
     * its tools and falls back to today's behavior of guessing a genre when it calls
     * {@code create_movie}, so failing the whole request over a missing hint would trade a real
     * capability loss for a cosmetic one.
     */
    private String genresSection() {
        try {
            final String genres = mcpKnowledgeService.readResource(GENRES_RESOURCE_URI);
            if (genres == null || genres.isBlank()) {
                return "";
            }
            return "\n\n--- GÉNEROS VÁLIDOS DEL CATÁLOGO (recurso MCP catalog://genres) ---\n" + genres;
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el recurso MCP '{}' para enriquecer el prompt del catálogo: {}",
                    GENRES_RESOURCE_URI, e.getMessage());
            return "";
        }
    }
}
