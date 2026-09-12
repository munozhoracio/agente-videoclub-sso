package ar.unrn.video.agent.config;

import ar.unrn.video.agent.auth.KeycloakTokenService;
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

@Configuration
public class McpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfiguration.class);

    @Bean(destroyMethod = "close")
    public McpSyncClient mcpSyncClient(
            @Value("${videoclub.mcp.url}") final String mcpUrl,
            final KeycloakTokenService tokenService) {

        log.info("Configuring MCP Sync Client for VideoClub at {}", mcpUrl);

        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(mcpUrl)
                .connectTimeout(Duration.ofSeconds(10))
                .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    final String token = tokenService.getAccessToken();
                    builder.header("Authorization", "Bearer " + token);
                })
                .build();

        final McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(15))
                .build();

        try {
            log.info("Initializing MCP session with VideoClub server...");
            client.initialize();
            log.info("MCP session initialized successfully");
        } catch (Exception e) {
            log.warn("Could not initialize MCP session at startup: {}", e.getMessage());
        }

        return client;
    }

    @Bean
    public SyncMcpToolCallbackProvider mcpToolCallbackProvider(final McpSyncClient mcpSyncClient) {
        return new SyncMcpToolCallbackProvider(mcpSyncClient);
    }
}
