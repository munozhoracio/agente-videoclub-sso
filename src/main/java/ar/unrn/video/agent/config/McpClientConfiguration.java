package ar.unrn.video.agent.config;

import ar.unrn.video.agent.auth.TokenRelayService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
public class McpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfiguration.class);

    /**
     * Builds the MCP client used for every outbound call to the VideoClub MCP server.
     *
     * <h2>The discovery window</h2>
     *
     * <p>One transport serves two kinds of request with two different identities. The
     * {@code discoveryWindow} flag is what separates them, and it is open for exactly one moment:
     * the {@code initialize()} handshake below, which runs while this {@code @Bean} is still being
     * created. No HTTP endpoint is serving yet at that point, so no user request can slip through
     * the window; the {@code finally} block closes it permanently before the context finishes
     * refreshing.
     *
     * <p>After that, the customizer only ever relays the caller's own token, and
     * {@link TokenRelayService#getUserBearerToken()} throws when there is no caller — the call
     * fails instead of silently running as the service account. That also covers a mid-request
     * re-initialization: the window is already closed, so the handshake is retried as the user.
     *
     * <p>A failed startup handshake is logged and tolerated rather than fatal. Tool discovery is
     * lazy — {@code SyncMcpToolCallbackProvider} caches callbacks on first use, which happens on a
     * user request — so the first caller's own token recovers the session if Keycloak or the MCP
     * server was unreachable at boot.
     */
    @Bean(destroyMethod = "close")
    public McpSyncClient mcpSyncClient(
            @Value("${videoclub.mcp.url}") final String mcpUrl,
            final TokenRelayService tokenRelayService) {

        log.info("Configuring MCP Sync Client for VideoClub at {}", mcpUrl);

        final AtomicBoolean discoveryWindow = new AtomicBoolean(true);

        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(mcpUrl)
                .connectTimeout(Duration.ofSeconds(10))
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    final String token = discoveryWindow.get()
                            ? tokenRelayService.getDiscoveryToken()
                            : tokenRelayService.getUserBearerToken();
                    builder.header("Authorization", "Bearer " + token);
                })
                .build();

        final McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(15))
                .build();

        try {
            log.info("Initializing MCP session with VideoClub server using the service account...");
            client.initialize();
            log.info("MCP session initialized successfully");
        } catch (Exception e) {
            log.warn("Could not initialize MCP session at startup: {}. "
                    + "The session will be established on the first authenticated request.", e.getMessage());
        } finally {
            discoveryWindow.set(false);
            log.info("Discovery window closed; every further MCP call relays the caller's own token");
        }

        return client;
    }

    @Bean
    public SyncMcpToolCallbackProvider mcpToolCallbackProvider(final McpSyncClient mcpSyncClient) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient)
                .build();
    }
}
