package ar.unrn.video.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Facade over the two {@link McpSyncClient} beans wired in
 * {@code McpClientConfiguration},
 * giving the rest of the agent one place to read an MCP Resource without
 * knowing which backend
 * service owns which URI.
 *
 * <p>
 * Every call here must run while handling a user request. The clients relay the
 * caller's
 * JWT through {@code transportContextProvider}, which captures it from
 * {@code SecurityContextHolder} on the thread that starts the operation; there
 * is no user
 * identity to capture at startup, in a constructor, or in a
 * {@code @PostConstruct} method, and
 * calling from there fails once the discovery window in
 * {@code McpClientConfiguration} closes.
 *
 * <p>
 * {@link #readResource(String)} routes by URI scheme to the catalog or
 * membership MCP
 * server and is used to inject resource context (e.g. catalog genres) into
 * sub-agent prompts.
 */
@Service
public class McpKnowledgeService {

    private static final String CATALOG_SCHEME = "catalog://";
    private static final String MEMBERSHIP_SCHEME = "membership://";

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
     * <p>
     * Routing is by the URI's scheme only — {@code catalog://} always goes to the
     * catalog
     * client, {@code membership://} always to the membership client. An unknown or
     * missing
     * scheme throws instead of falling back to either client by elimination, for
     * the same
     * reason {@code AbstractDomainSubAgent} fails fast instead of answering with no
     * tools: a
     * mistyped URI sent to the wrong server comes back as "resource not found",
     * which reads as
     * a data problem when it is actually a routing bug. Failing fast here keeps
     * that distinction
     * visible instead of silently guessing a server.
     *
     * @throws IllegalArgumentException if the URI has no recognized scheme
     */
    public String readResource(final String uri) {
        final McpSyncClient client = clientForUri(uri);
        final McpSchema.ReadResourceResult result = client.readResource(McpSchema.ReadResourceRequest.builder(uri).build());
        return result.contents().stream()
                .filter(McpSchema.TextResourceContents.class::isInstance)
                .map(McpSchema.TextResourceContents.class::cast)
                .map(McpSchema.TextResourceContents::text)
                .collect(Collectors.joining());
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
}
