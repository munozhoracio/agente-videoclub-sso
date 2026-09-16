package ar.unrn.video.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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
        final McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents("catalog://genres", "text/markdown", "- ACTION")));
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
        final McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents("membership://socios/1", "text/markdown", "Socio: Juan")));
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
        final McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("catalog://genres", "text/markdown", "- ACTION\n"),
                new McpSchema.TextResourceContents("catalog://genres", "text/markdown", "- COMEDY")));
        when(catalogMcpClient.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(result);

        final String text = service.readResource("catalog://genres");

        assertThat(text).isEqualTo("- ACTION\n- COMEDY");
    }

    @Test
    @DisplayName("Throws for an unknown server key in getPrompt and never touches either client")
    void throwsForUnknownServerInGetPrompt() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);

        assertThatThrownBy(() -> service.getPrompt("unknown", "some-prompt", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");

        verifyNoInteractions(catalogMcpClient, membershipMcpClient);
    }

    @Test
    @DisplayName("Keeps resource templates separate from fixed resources in introspection")
    void keepsTemplatesSeparateFromFixedResources() {
        final McpKnowledgeService service = new McpKnowledgeService(catalogMcpClient, membershipMcpClient);
        final McpSchema.Resource genresResource = McpSchema.Resource.builder("catalog://genres", "catalog_genres").build();
        final McpSchema.ResourceTemplate movieTemplate =
                McpSchema.ResourceTemplate.builder("catalog://movies/{id}", "movie_card").build();

        when(catalogMcpClient.listResources()).thenReturn(new McpSchema.ListResourcesResult(List.of(genresResource), null));
        when(catalogMcpClient.listResourceTemplates())
                .thenReturn(new McpSchema.ListResourceTemplatesResult(List.of(movieTemplate), null));
        when(membershipMcpClient.listResources()).thenReturn(new McpSchema.ListResourcesResult(List.of(), null));
        when(membershipMcpClient.listResourceTemplates())
                .thenReturn(new McpSchema.ListResourceTemplatesResult(List.of(), null));

        final List<Map<String, Object>> resources = service.listResources();
        final List<Map<String, Object>> templates = service.listResourceTemplates();

        assertThat(resources).hasSize(1);
        assertThat(resources.get(0)).containsEntry("uri", "catalog://genres");
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0)).containsEntry("uriTemplate", "catalog://movies/{id}");
        assertThat(resources).noneMatch(entry -> entry.containsValue("catalog://movies/{id}"));
    }
}
