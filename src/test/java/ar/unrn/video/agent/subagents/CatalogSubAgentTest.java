package ar.unrn.video.agent.subagents;

import ar.unrn.video.agent.mcp.McpKnowledgeService;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogSubAgentTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Mock
    private McpKnowledgeService mcpKnowledgeService;

    @Test
    @DisplayName("Throws IllegalStateException and refuses to execute if no catalog MCP tools are available (fail-fast against hallucination)")
    void shouldFailFastWhenNoCatalogToolsDiscovered() {
        final ToolCallback unrelatedTool = mock(ToolCallback.class);
        final ToolDefinition unrelatedDef = mock(ToolDefinition.class);
        when(unrelatedDef.name()).thenReturn("unrelated_tool");
        when(unrelatedTool.getToolDefinition()).thenReturn(unrelatedDef);

        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{unrelatedTool});

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);
        final ExecutionTracker tracker = new ExecutionTracker();

        assertThatThrownBy(() -> agent.execute("¿Qué películas hay?", tracker, "TestUser"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falla crítica en CatalogSubAgent")
                .hasMessageContaining("Catálogo de Películas");

        assertThat(tracker.getAgentsInvoked()).contains("CatalogSubAgent");
    }

    @Test
    @DisplayName("Includes the catalog://genres resource content as a delimited section when the MCP read succeeds")
    void shouldIncludeGenresSectionWhenResourceReadSucceeds() {
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenReturn("- ACTION\n- COMEDY\n- DRAMA");

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).contains("ACTION", "COMEDY", "DRAMA", "catalog://genres");
    }

    @Test
    @DisplayName("Omits the genres section without throwing when the MCP resource read fails")
    void shouldOmitGenresSectionWhenResourceReadFails() {
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenThrow(new RuntimeException("catalog-service unreachable"));

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).doesNotContain("catalog-service unreachable");
    }
}
