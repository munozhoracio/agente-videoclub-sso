package ar.unrn.video.agent.rest;

import ar.unrn.video.agent.model.AgentStreamEvent;
import ar.unrn.video.agent.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SecurityContext securityContext;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentService);
    }

    @Test
    void chatStreamRejectsEmptyPrompt() {
        final SseEmitter emitter = controller.chatStream(new AgentController.ChatRequest("", "session-1"));
        assertNotNull(emitter);
        verifyNoInteractions(agentService);
    }

    @Test
    void chatStreamDelegatesToService() {
        SecurityContextHolder.setContext(securityContext);

        doAnswer(invocation -> {
            final Consumer<AgentStreamEvent> consumer = invocation.getArgument(2);
            consumer.accept(AgentStreamEvent.status("CatalogSubAgent", "Buscando..."));
            consumer.accept(AgentStreamEvent.delta("Hola "));
            consumer.accept(AgentStreamEvent.delta("mundo"));
            consumer.accept(AgentStreamEvent.done("session-1", List.of("CatalogSubAgent"), List.of("search_movies"), List.of(), false));
            return null;
        }).when(agentService).chatStream(eq("test query"), eq("session-1"), any());

        final SseEmitter emitter = controller.chatStream(new AgentController.ChatRequest("test query", "session-1"));
        assertNotNull(emitter);

        verify(agentService, timeout(1000)).chatStream(eq("test query"), eq("session-1"), any());
        SecurityContextHolder.clearContext();
    }
}
