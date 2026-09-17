package ar.unrn.video.agent.subagents;

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
class MembershipSubAgentTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Test
    @DisplayName("Throws IllegalStateException and refuses to execute if the dedicated membership MCP provider exposes no tools (fail-fast against hallucination)")
    void shouldFailFastWhenNoMembershipToolsDiscovered() {
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        final MembershipSubAgent agent = new MembershipSubAgent(chatClientBuilder, toolCallbackProvider);
        final ExecutionTracker tracker = new ExecutionTracker();

        assertThatThrownBy(() -> agent.execute("Listar socios", tracker, "AdminUser"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falla crítica en MembershipSubAgent")
                .hasMessageContaining("Socios y Membresías");

        assertThat(tracker.getAgentsInvoked()).contains("MembershipSubAgent");
    }

    @Test
    @DisplayName("Resolves every tool the dedicated membership MCP provider exposes, with no name filtering")
    void shouldResolveAllToolsFromDedicatedProviderWithoutFiltering() {
        final ToolCallback newTool = mock(ToolCallback.class);
        final ToolDefinition newToolDef = mock(ToolDefinition.class);
        when(newToolDef.name()).thenReturn("delete_movie");
        when(newTool.getToolDefinition()).thenReturn(newToolDef);

        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{newTool});

        final MembershipSubAgent agent = new MembershipSubAgent(chatClientBuilder, toolCallbackProvider);
        final ExecutionTracker tracker = new ExecutionTracker();

        final ToolCallback[] resolvedTools = agent.resolveDomainTools(tracker);

        assertThat(resolvedTools).hasSize(1);
        assertThat(resolvedTools[0].getToolDefinition().name()).isEqualTo("delete_movie");
    }
}
