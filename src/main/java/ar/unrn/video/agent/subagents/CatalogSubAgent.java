package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.mcp.McpKnowledgeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Specialized Sub-Agent responsible for the movie catalog domain.
 * Connects exclusively to all tools exposed by catalog-service's MCP server.
 */
@Component
public class CatalogSubAgent extends AbstractDomainSubAgent {

    private static final String GENRES_RESOURCE_URI = "catalog://genres";
    private static final String MOVIE_CREATION_PROCEDURE_RESOURCE_URI = "catalog://procedures/movie-creation";

    private final McpKnowledgeService mcpKnowledgeService;

    public CatalogSubAgent(
            final ChatClient.Builder chatClientBuilder,
            @Qualifier("catalogTools") final SyncMcpToolCallbackProvider toolCallbackProvider,
            final McpKnowledgeService mcpKnowledgeService) {
        super("CatalogSubAgent", "Catálogo de Películas", chatClientBuilder, toolCallbackProvider);
        this.mcpKnowledgeService = mcpKnowledgeService;
    }

    @Override
    protected String buildSystemPrompt(final String callerName) {
        return String.format(
                "Sos el Sub-Agente Especialista en Catálogo de Películas de VideoClub UNRN. "
                + "Atendés consultas de %s sobre películas, estrenos, géneros y disponibilidad en el catálogo. "
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
        )
                + resourceSection(MOVIE_CREATION_PROCEDURE_RESOURCE_URI,
                        "--- PROCEDIMIENTO DE ALTA DE PELÍCULAS (recurso MCP "
                        + "catalog://procedures/movie-creation) ---")
                + resourceSection(GENRES_RESOURCE_URI,
                        "--- GÉNEROS VÁLIDOS DEL CATÁLOGO (recurso MCP catalog://genres) ---");
    }

    /**
     * Reads one MCP resource and renders it as a delimited addendum to the system prompt, so the
     * model has the catalog's real data instead of inventing or guessing it — for example a genre
     * spelling (e.g. "Sci-Fi" instead of {@code SCIENCE_FICTION}) that the database's
     * {@code CHECK} constraint would reject, or a movie-creation procedure that drifts from the
     * one {@code catalog-service} actually enforces the way this sub-agent's own hardcoded create
     * sentence once did.
     *
     * <p>This read happens on every call, at query time, never in the constructor: the caller's
     * JWT only exists while a user request is being handled, and {@code McpClientConfiguration}
     * refuses MCP calls made outside of one.
     *
     * <p>A failed read only degrades the prompt, it does not fail the request. This is
     * deliberately different from the fail-fast in {@link AbstractDomainSubAgent}, which refuses
     * to run when no domain tools are discovered because answering without tools means the model
     * invents data outright. Losing one resource hint does not create that risk: the model still
     * has its tools and falls back to guessing (a genre spelling, or the create-a-movie procedure)
     * when it calls a tool, so failing the whole request over a missing hint would trade a real
     * capability loss for a cosmetic one.
     *
     * @param uri the MCP resource URI to read, e.g. {@code catalog://genres}
     * @param header the delimited header prefixed to the resource's content
     */
    private String resourceSection(final String uri, final String header) {
        try {
            final String content = mcpKnowledgeService.readResource(uri);
            if (content == null || content.isBlank()) {
                return "";
            }
            return "\n\n" + header + "\n" + content;
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el recurso MCP '{}' para enriquecer el prompt del catálogo: {}",
                    uri, e.getMessage());
            return "";
        }
    }
}
