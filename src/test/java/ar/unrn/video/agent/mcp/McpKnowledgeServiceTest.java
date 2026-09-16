package ar.unrn.video.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpKnowledgeServiceTest {

    @Mock
    private McpSyncClient catalogMcpClient;

    @Mock
    private McpSyncClient membershipMcpClient;

    @Test
    @DisplayName("Routes catalog:// URIs to the catalog client only")
    void routesCatalogSchemeToCatalogClientOnly() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);
        final McpSchema.ReadResourceResult result = McpSchema.ReadResourceResult.builder(
                List.of(textContents("catalog://genres", "- ACTION"))).build();
        when(catalogMcpClient.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(result);

        final String text = service.readResource("catalog://genres");

        assertThat(text).isEqualTo("- ACTION");
        verify(catalogMcpClient).readResource(any(McpSchema.ReadResourceRequest.class));
        verifyNoInteractions(membershipMcpClient);
    }

    @Test
    @DisplayName("Routes membership:// URIs to the membership client only")
    void routesMembershipSchemeToMembershipClientOnly() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);
        final McpSchema.ReadResourceResult result = McpSchema.ReadResourceResult.builder(
                List.of(textContents("membership://socios/1", "Socio: Juan"))).build();
        when(membershipMcpClient.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(result);

        final String text = service.readResource("membership://socios/1");

        assertThat(text).isEqualTo("Socio: Juan");
        verify(membershipMcpClient).readResource(any(McpSchema.ReadResourceRequest.class));
        verifyNoInteractions(catalogMcpClient);
    }

    @Test
    @DisplayName("Throws for an unknown scheme and never touches either client (fail-fast instead of choosing by elimination)")
    void throwsForUnknownSchemeAndTouchesNoClient() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);

        assertThatThrownBy(() -> service.readResource("ftp://unknown/1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ftp://unknown/1");

        verifyNoInteractions(catalogMcpClient, membershipMcpClient);
    }

    @Test
    @DisplayName("Throws for a null URI and never touches either client")
    void throwsForNullUriAndTouchesNoClient() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);

        assertThatThrownBy(() -> service.readResource(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(catalogMcpClient, membershipMcpClient);
    }

    @Test
    @DisplayName("Concatenates text from every TextResourceContents entry in order")
    void concatenatesTextFromMultipleContents() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);
        final McpSchema.ReadResourceResult result = McpSchema.ReadResourceResult.builder(List.of(
                textContents("catalog://genres", "- ACTION\n"),
                textContents("catalog://genres", "- COMEDY"))).build();
        when(catalogMcpClient.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(result);

        final String text = service.readResource("catalog://genres");

        assertThat(text).isEqualTo("- ACTION\n- COMEDY");
    }

    private static McpSchema.TextResourceContents textContents(final String uri, final String text) {
        return McpSchema.TextResourceContents.builder(uri, text).mimeType("text/markdown").build();
    }
}
