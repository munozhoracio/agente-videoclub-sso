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
    @DisplayName("Throws IllegalStateException and refuses to execute if the dedicated catalog MCP provider exposes no tools (fail-fast against hallucination)")
    void shouldFailFastWhenNoCatalogToolsDiscovered() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);
        final ExecutionTracker tracker = new ExecutionTracker();

        assertThatThrownBy(() -> agent.execute("¿Qué películas hay?", tracker, "TestUser"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falla crítica en CatalogSubAgent")
                .hasMessageContaining("Catálogo de Películas");

        assertThat(tracker.getAgentsInvoked()).contains("CatalogSubAgent");
    }

    @Test
    @DisplayName("Resolves every tool the dedicated catalog MCP provider exposes, with no name filtering")
    void shouldResolveAllToolsFromDedicatedProviderWithoutFiltering() {
        final ToolCallback newTool = mock(ToolCallback.class);
        final ToolDefinition newToolDef = mock(ToolDefinition.class);
        when(newToolDef.name()).thenReturn("delete_movie");
        when(newTool.getToolDefinition()).thenReturn(newToolDef);

        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{newTool});

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);
        final ExecutionTracker tracker = new ExecutionTracker();

        final ToolCallback[] resolvedTools = agent.resolveDomainTools(tracker);

        assertThat(resolvedTools).hasSize(1);
        assertThat(resolvedTools[0].getToolDefinition().name()).isEqualTo("delete_movie");
    }

    @Test
    @DisplayName("Includes the catalog://genres resource content as a delimited section when the MCP read succeeds")
    void shouldIncludeGenresSectionWhenResourceReadSucceeds() {
        // buildSystemPrompt now also reads catalog://procedures/movie-creation; stub it here too,
        // otherwise MockitoExtension's strict stubbing throws PotentialStubbingProblem on that
        // unstubbed argument, which resourceSection would silently swallow as a RuntimeException.
        when(mcpKnowledgeService.readResource("catalog://procedures/movie-creation")).thenReturn("");
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenReturn("- ACTION\n- COMEDY\n- DRAMA");

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).contains("ACTION", "COMEDY", "DRAMA", "catalog://genres");
    }

    @Test
    @DisplayName("Omits the genres section without throwing when the MCP resource read fails")
    void shouldOmitGenresSectionWhenResourceReadFails() {
        // Same reason as above: stub the procedure URI too, so this test stays isolated to the
        // genres-read failure it means to exercise.
        when(mcpKnowledgeService.readResource("catalog://procedures/movie-creation")).thenReturn("");
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenThrow(new RuntimeException("catalog-service unreachable"));

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).doesNotContain("catalog-service unreachable");
    }

    @Test
    @DisplayName("Includes the catalog://procedures/movie-creation resource content as a delimited section when the MCP read succeeds")
    void shouldIncludeMovieCreationProcedureSectionWhenResourceReadSucceeds() {
        when(mcpKnowledgeService.readResource("catalog://procedures/movie-creation"))
                .thenReturn("1. Busca primero con `search_movies` usando el titulo.");
        when(mcpKnowledgeService.readResource("catalog://genres")).thenReturn("");

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).contains(
                "search_movies", "catalog://procedures/movie-creation", "PROCEDIMIENTO DE ALTA DE PELÍCULAS");
    }

    @Test
    @DisplayName("Omits the procedure section without throwing when the MCP resource read fails, and the genres section still appears")
    void shouldOmitProcedureSectionWhenResourceReadFailsButKeepGenres() {
        when(mcpKnowledgeService.readResource("catalog://procedures/movie-creation"))
                .thenThrow(new RuntimeException("catalog-service unreachable"));
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenReturn("- ACTION\n- COMEDY\n- DRAMA");

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).doesNotContain("catalog-service unreachable");
        assertThat(systemPrompt).doesNotContain("PROCEDIMIENTO DE ALTA DE PELÍCULAS");
        assertThat(systemPrompt).contains("ACTION", "COMEDY", "DRAMA", "catalog://genres");
    }

    @Test
    @DisplayName("Never hardcodes the create_movie sentence: the movie-creation procedure only comes from the MCP resource")
    void shouldNotHardcodeTheCreateMovieSentence() {
        when(mcpKnowledgeService.readResource("catalog://procedures/movie-creation"))
                .thenThrow(new RuntimeException("catalog-service unreachable"));
        when(mcpKnowledgeService.readResource("catalog://genres"))
                .thenThrow(new RuntimeException("catalog-service unreachable"));

        final CatalogSubAgent agent = new CatalogSubAgent(chatClientBuilder, toolCallbackProvider, mcpKnowledgeService);

        final String systemPrompt = agent.buildSystemPrompt("TestUser");

        assertThat(systemPrompt).doesNotContain(
                "También podés CREAR nuevas películas usando la herramienta create_movie");
    }
}
