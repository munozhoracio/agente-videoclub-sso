package ar.unrn.video.agent.orchestrator;

import ar.unrn.video.agent.generativeui.GenerativeUiExtractor;
import ar.unrn.video.agent.subagents.CatalogSubAgent;
import ar.unrn.video.agent.subagents.MembershipSubAgent;
import ar.unrn.video.agent.tracker.ExecutionTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorToolsTest {

    @Mock
    private CatalogSubAgent catalogSubAgent;

    @Mock
    private MembershipSubAgent membershipSubAgent;

    @Mock
    private SecurityContext mockSecurityContext;

    @Mock
    private Authentication mockAuthentication;

    @Mock
    private GenerativeUiExtractor extractor;

    @Test
    void consultCatalogAgentRestoresSecurityContextOnWorkerThread() throws ExecutionException, InterruptedException {
        when(mockSecurityContext.getAuthentication()).thenReturn(mockAuthentication);

        final ExecutionTracker tracker = new ExecutionTracker();

        final OrchestratorTools tools = new OrchestratorTools(
                catalogSubAgent,
                membershipSubAgent,
                tracker,
                extractor,
                "Test User",
                null,
                mockSecurityContext
        );

        when(extractor.extract(anyString())).thenReturn(new GenerativeUiExtractor.ExtractionResult("Found matrix movies", java.util.List.of()));

        when(catalogSubAgent.execute(eq("matrix"), any(), any())).thenAnswer(invocation -> {
            // Verify that during subagent execution, SecurityContextHolder has the restored context
            final Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
            assertEquals(mockAuthentication, currentAuth);
            return "Found matrix movies";
        });

        // Run tool invocation on a completely separate thread where SecurityContextHolder is empty
        final CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.clearContext();
            return tools.consultCatalogAgent("matrix");
        });

        final String result = future.get();
        assertEquals("Found matrix movies", result);
        verify(catalogSubAgent).execute(eq("matrix"), any(), any());
    }
}
