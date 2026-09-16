package ar.unrn.video.agent.config;

import ar.unrn.video.agent.auth.TokenRelayService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires one MCP client per backend service.
 *
 * <p>The VideoClub backend used to be a single server exposing every tool. It is now two
 * independent services — catalog-service and membership-service — each with its own MCP
 * endpoint, so there are two clients here instead of one. Everything about how a client
 * relays the caller's identity is unchanged; see {@link #buildClient} for that mechanism.
 *
 * <p>Three {@link SyncMcpToolCallbackProvider} beans are published:
 * <ul>
 *   <li>{@code catalogTools} and {@code membershipTools}, each bound to one service, so a
 *       sub-agent can only reach the tools of its own domain;</li>
 *   <li>a {@code @Primary} aggregate over both, which is what {@code AgentService} injects by
 *       type to answer {@code GET /api/agent/tools}. Without the {@code @Primary} marker that
 *       injection point becomes ambiguous and the context fails to start.</li>
 * </ul>
 */
@Configuration
public class McpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfiguration.class);

    /** Key under which the caller's JWT travels inside the MCP transport context. */
    private static final String CALLER_TOKEN_KEY = "videoclub.caller-token";

    @Bean(destroyMethod = "close")
    public McpSyncClient catalogMcpClient(
            @Value("${videoclub.mcp.catalog-url}") final String mcpUrl,
            final TokenRelayService tokenRelayService) {
        return buildClient("catalog-service", mcpUrl, tokenRelayService);
    }

    @Bean(destroyMethod = "close")
    public McpSyncClient membershipMcpClient(
            @Value("${videoclub.mcp.membership-url}") final String mcpUrl,
            final TokenRelayService tokenRelayService) {
        return buildClient("membership-service", mcpUrl, tokenRelayService);
    }

    /**
     * Builds the MCP client used for every outbound call to one VideoClub MCP server.
     *
     * <h2>Carrying the caller's identity across threads</h2>
     *
     * <p>{@code SecurityContextHolder} is backed by a {@code ThreadLocal}, and this client does not
     * write its HTTP requests on the thread that asked for them: a single {@code tools/list} is a
     * reactive pipeline that hops onto the MCP client's own workers. Reading the identity inside
     * the request customizer therefore finds it only sometimes — whichever thread happens to build
     * that particular request — which is not a property anything can be built on.
     *
     * <p>So the identity is captured rather than looked up. {@code transportContextProvider} runs
     * on the thread that <em>starts</em> the operation, where the servlet's {@code SecurityContext}
     * is still visible, and the resulting {@link McpTransportContext} travels with the request
     * through the Reactor context, which does survive thread hops. The customizer then reads the
     * token out of that context instead of out of a {@code ThreadLocal} that may belong to a
     * different thread entirely.
     *
     * <h2>The discovery window</h2>
     *
     * <p>One transport serves two kinds of request with two different identities. The
     * {@code discoveryWindow} flag separates them, and it is open for exactly one moment: the
     * {@code initialize()} handshake below, which runs while the calling {@code @Bean} method is
     * still executing. No HTTP endpoint is serving yet at that point, so no user request can slip
     * through the window; the {@code finally} block closes it permanently before the context
     * finishes refreshing.
     *
     * <p><strong>The flag is local to this method on purpose, so each client owns its own.</strong>
     * A single shared flag would be closed by whichever client initialized first, and the second
     * client's own {@code initialize()} would then fall through to
     * {@link TokenRelayService#getUserBearerToken()} — which throws, because there is no caller at
     * startup. Two clients, two windows.
     *
     * <p>After that, only a captured caller token is accepted. When there is none and the window is
     * closed, {@link TokenRelayService#getUserBearerToken()} throws — the call fails instead of
     * silently running as the service account.
     *
     * <p>A failed startup handshake is logged and tolerated rather than fatal, per client: one
     * service being down at boot must not take the agent with it. Tool discovery is lazy —
     * {@code SyncMcpToolCallbackProvider} caches callbacks on first use, which happens on a user
     * request — so the first caller's own token recovers the session if Keycloak or that MCP server
     * was unreachable at boot. That recovery is exactly the path that the captured context makes
     * reliable: the re-initialization runs under the identity of whoever triggered it, no matter
     * which worker thread ends up sending its requests. The {@code serviceName} in every log line
     * is what tells the two clients apart when only one of them failed.
     */
    private static McpSyncClient buildClient(final String serviceName,
                                             final String mcpUrl,
                                             final TokenRelayService tokenRelayService) {

        log.info("Configuring MCP Sync Client for {} at {}", serviceName, mcpUrl);

        final AtomicBoolean discoveryWindow = new AtomicBoolean(true);

        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(mcpUrl)
                .connectTimeout(Duration.ofSeconds(10))
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> builder.header(
                        "Authorization",
                        "Bearer " + resolveToken(ctx, discoveryWindow.get(), tokenRelayService)))
                .build();

        final McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(15))
                .transportContextProvider(() -> captureCallerToken(tokenRelayService))
                .build();

        try {
            log.info("Initializing MCP session with {} using the service account...", serviceName);
            client.initialize();
            log.info("MCP session with {} initialized successfully", serviceName);
        } catch (Exception e) {
            log.warn("Could not initialize MCP session with {} at startup: {}. "
                    + "The session will be established on the first authenticated request.",
                    serviceName, e.getMessage());
        } finally {
            discoveryWindow.set(false);
            log.info("Discovery window for {} closed; every further MCP call relays the caller's own token",
                    serviceName);
        }

        return client;
    }

    /**
     * Snapshots the caller's JWT on the thread that starts an MCP operation.
     *
     * <p>Returns {@link McpTransportContext#EMPTY} when no user identity is present, which is the
     * normal case for the startup handshake.
     */
    private static McpTransportContext captureCallerToken(final TokenRelayService tokenRelayService) {
        return tokenRelayService.currentUserToken()
                .map(token -> McpTransportContext.create(Map.of(CALLER_TOKEN_KEY, token)))
                .orElse(McpTransportContext.EMPTY);
    }

    /**
     * Picks the bearer token for one outbound HTTP request.
     *
     * <p>This runs on whichever thread the transport uses, so it must not consult
     * {@code SecurityContextHolder} for the happy path — the captured context is the source of
     * truth. The final branch is the deliberate failure: no captured identity outside the discovery
     * window means the call is refused rather than downgraded to the service account.
     */
    private static String resolveToken(final McpTransportContext ctx,
                                       final boolean discoveryWindowOpen,
                                       final TokenRelayService tokenRelayService) {
        if (ctx != null && ctx.get(CALLER_TOKEN_KEY) instanceof String callerToken) {
            return callerToken;
        }
        if (discoveryWindowOpen) {
            return tokenRelayService.getDiscoveryToken();
        }
        return tokenRelayService.getUserBearerToken();
    }

    @Bean
    @Qualifier("catalogTools")
    public SyncMcpToolCallbackProvider catalogToolCallbackProvider(final McpSyncClient catalogMcpClient) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(catalogMcpClient)
                .build();
    }

    @Bean
    @Qualifier("membershipTools")
    public SyncMcpToolCallbackProvider membershipToolCallbackProvider(final McpSyncClient membershipMcpClient) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(membershipMcpClient)
                .build();
    }

    /**
     * Every tool of both services, for the callers that legitimately want the whole inventory —
     * today, {@code AgentService} serving {@code GET /api/agent/tools}.
     */
    @Bean
    @Primary
    public SyncMcpToolCallbackProvider allToolCallbackProvider(final McpSyncClient catalogMcpClient,
                                                               final McpSyncClient membershipMcpClient) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(catalogMcpClient, membershipMcpClient)
                .build();
    }
}
