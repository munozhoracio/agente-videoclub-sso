package ar.unrn.video.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Facade over the two {@link McpSyncClient} beans wired in {@code McpClientConfiguration},
 * giving the rest of the agent one place to read MCP Resources and fetch MCP Prompts without
 * knowing which backend service owns which URI or prompt name.
 *
 * <p>Every call here must run while handling a user request. The clients relay the caller's
 * JWT through {@code transportContextProvider}, which captures it from
 * {@code SecurityContextHolder} on the thread that starts the operation; there is no user
 * identity to capture at startup, in a constructor, or in a {@code @PostConstruct} method, and
 * calling from there fails once the discovery window in {@code McpClientConfiguration} closes.
 */
@Service
public class McpKnowledgeService {

    private static final String CATALOG_SCHEME = "catalog://";
    private static final String MEMBERSHIP_SCHEME = "membership://";

    private static final String CATALOG_SERVER = "catalog";
    private static final String MEMBERSHIP_SERVER = "membership";

    private final McpSyncClient catalogMcpClient;
    private final McpSyncClient membershipMcpClient;

    public McpKnowledgeService(final McpSyncClient catalogMcpClient, final McpSyncClient membershipMcpClient) {
        this.catalogMcpClient = catalogMcpClient;
        this.membershipMcpClient = membershipMcpClient;
    }

    /**
     * Reads one MCP resource and returns the concatenated text of its
     * {@link McpSchema.TextResourceContents} entries.
     *
     * <p>Routing is by the URI's scheme only — {@code catalog://} always goes to the catalog
     * client, {@code membership://} always to the membership client. An unknown or missing
     * scheme throws instead of falling back to either client by elimination, for the same
     * reason {@code AbstractDomainSubAgent} fails fast instead of answering with no tools: a
     * mistyped URI sent to the wrong server comes back as "resource not found", which reads as
     * a data problem when it is actually a routing bug. Failing fast here keeps that distinction
     * visible instead of silently guessing a server.
     *
     * @throws IllegalArgumentException if the URI has no recognized scheme
     */
    public String readResource(final String uri) {
        final McpSyncClient client = clientForUri(uri);
        final McpSchema.ReadResourceResult result = client.readResource(new McpSchema.ReadResourceRequest(uri));
        return result.contents().stream()
                .filter(McpSchema.TextResourceContents.class::isInstance)
                .map(McpSchema.TextResourceContents.class::cast)
                .map(McpSchema.TextResourceContents::text)
                .collect(Collectors.joining());
    }

    /**
     * Fetches one MCP prompt by name from the given server, filling in its arguments.
     *
     * @param server    {@code "catalog"} or {@code "membership"}
     * @param name      the prompt's registered name (e.g. {@code catalog-alta-pelicula})
     * @param arguments the prompt's arguments, as declared by the server
     * @throws IllegalArgumentException if {@code server} is neither {@code "catalog"} nor {@code "membership"}
     */
    public McpSchema.GetPromptResult getPrompt(final String server, final String name,
                                               final Map<String, Object> arguments) {
        final McpSyncClient client = clientForServer(server);
        return client.getPrompt(new McpSchema.GetPromptRequest(name, arguments));
    }

    /**
     * Lists the fixed resources of both servers, each entry tagged with its owning server.
     *
     * <p>Fixed resources and resource templates are distinct MCP primitives, listed by separate
     * protocol operations ({@code resources/list} vs {@code resources/templates/list}); see
     * {@link #listResourceTemplates()} for the templates.
     */
    public List<Map<String, Object>> listResources() {
        final List<Map<String, Object>> out = new ArrayList<>();
        appendResources(out, CATALOG_SERVER, catalogMcpClient.listResources().resources());
        appendResources(out, MEMBERSHIP_SERVER, membershipMcpClient.listResources().resources());
        return out;
    }

    /**
     * Lists the resource templates of both servers, each entry tagged with its owning server and
     * kept separate from {@link #listResources()}'s fixed resources.
     */
    public List<Map<String, Object>> listResourceTemplates() {
        final List<Map<String, Object>> out = new ArrayList<>();
        appendResourceTemplates(out, CATALOG_SERVER, catalogMcpClient.listResourceTemplates().resourceTemplates());
        appendResourceTemplates(out, MEMBERSHIP_SERVER, membershipMcpClient.listResourceTemplates().resourceTemplates());
        return out;
    }

    /**
     * Lists the prompts of both servers, each entry tagged with its owning server.
     */
    public List<Map<String, Object>> listPrompts() {
        final List<Map<String, Object>> out = new ArrayList<>();
        appendPrompts(out, CATALOG_SERVER, catalogMcpClient.listPrompts().prompts());
        appendPrompts(out, MEMBERSHIP_SERVER, membershipMcpClient.listPrompts().prompts());
        return out;
    }

    private McpSyncClient clientForUri(final String uri) {
        if (uri != null && uri.startsWith(CATALOG_SCHEME)) {
            return catalogMcpClient;
        }
        if (uri != null && uri.startsWith(MEMBERSHIP_SCHEME)) {
            return membershipMcpClient;
        }
        throw new IllegalArgumentException(String.format(
                "URI de recurso MCP desconocida: '%s'. Schemes soportados: %s, %s",
                uri, CATALOG_SCHEME, MEMBERSHIP_SCHEME));
    }

    private McpSyncClient clientForServer(final String server) {
        if (CATALOG_SERVER.equalsIgnoreCase(server)) {
            return catalogMcpClient;
        }
        if (MEMBERSHIP_SERVER.equalsIgnoreCase(server)) {
            return membershipMcpClient;
        }
        throw new IllegalArgumentException(String.format(
                "Servidor MCP desconocido: '%s'. Servidores soportados: %s, %s",
                server, CATALOG_SERVER, MEMBERSHIP_SERVER));
    }

    private void appendResources(final List<Map<String, Object>> out, final String server,
                                 final List<McpSchema.Resource> resources) {
        for (final McpSchema.Resource resource : resources) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("server", server);
            entry.put("type", "resource");
            entry.put("uri", resource.uri());
            entry.put("name", resource.name());
            entry.put("title", resource.title());
            entry.put("description", resource.description());
            entry.put("mimeType", resource.mimeType());
            out.add(entry);
        }
    }

    private void appendResourceTemplates(final List<Map<String, Object>> out, final String server,
                                         final List<McpSchema.ResourceTemplate> templates) {
        for (final McpSchema.ResourceTemplate template : templates) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("server", server);
            entry.put("type", "resourceTemplate");
            entry.put("uriTemplate", template.uriTemplate());
            entry.put("name", template.name());
            entry.put("title", template.title());
            entry.put("description", template.description());
            entry.put("mimeType", template.mimeType());
            out.add(entry);
        }
    }

    private void appendPrompts(final List<Map<String, Object>> out, final String server,
                               final List<McpSchema.Prompt> prompts) {
        for (final McpSchema.Prompt prompt : prompts) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("server", server);
            entry.put("name", prompt.name());
            entry.put("title", prompt.title());
            entry.put("description", prompt.description());
            entry.put("arguments", promptArguments(prompt));
            out.add(entry);
        }
    }

    private List<Map<String, Object>> promptArguments(final McpSchema.Prompt prompt) {
        if (prompt.arguments() == null) {
            return List.of();
        }
        final List<Map<String, Object>> arguments = new ArrayList<>();
        for (final McpSchema.PromptArgument argument : prompt.arguments()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", argument.name());
            entry.put("title", argument.title());
            entry.put("description", argument.description());
            entry.put("required", argument.required());
            arguments.add(entry);
        }
        return arguments;
    }
}
