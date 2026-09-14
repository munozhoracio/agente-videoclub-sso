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
    @DisplayName("Throws IllegalStateException and refuses to execute if no membership MCP tools are available (fail-fast against hallucination)")
    void shouldFailFastWhenNoMembershipToolsDiscovered() {
        final ToolCallback unrelatedTool = mock(ToolCallback.class);
        final ToolDefinition unrelatedDef = mock(ToolDefinition.class);
        when(unrelatedDef.name()).thenReturn("list_movies");
        when(unrelatedTool.getToolDefinition()).thenReturn(unrelatedDef);

        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{unrelatedTool});

        final MembershipSubAgent agent = new MembershipSubAgent(chatClientBuilder, toolCallbackProvider);
        final ExecutionTracker tracker = new ExecutionTracker();

        assertThatThrownBy(() -> agent.execute("Listar socios", tracker, "AdminUser"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falla crítica en MembershipSubAgent")
                .hasMessageContaining("Socios y Membresías");

        assertThat(tracker.getAgentsInvoked()).contains("MembershipSubAgent");
    }
}
