package ar.unrn.video.agent.subagents;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Specialized Sub-Agent responsible for customer membership, partners, and account records.
 * Connects exclusively to partner-related MCP tools (get_socio, list_socios).
 */
@Component
public class MembershipSubAgent extends AbstractDomainSubAgent {

    public static final Set<String> MEMBERSHIP_TOOL_NAMES = Set.of(
            "get_socio",
            "list_socios"
    );

    public MembershipSubAgent(
            final ChatClient.Builder chatClientBuilder,
            @Qualifier("membershipTools") final SyncMcpToolCallbackProvider toolCallbackProvider) {
        super("MembershipSubAgent", "Socios y Membresías", MEMBERSHIP_TOOL_NAMES, chatClientBuilder, toolCallbackProvider);
    }

    @Override
    protected String buildSystemPrompt(final String callerName) {
        return String.format(
                "Sos el Sub-Agente Especialista en Socios y Membresías de VideoClub UNRN. "
                + "Atendés a %s. "
                + "Tenés acceso a herramientas MCP para consultar los datos y padrón de socios (get_socio, list_socios). "
                + "Invocá SIEMPRE las herramientas MCP disponibles para obtener la información solicitada. "
                + "No supongas permisos de antemano: ejecutá la herramienta correspondiente. "
                + "Solo si la ejecución de la herramienta falla o devuelve error de autorización/acceso denegado, explicáselo amablemente al usuario. "
                + "Respondé de forma clara, profesional y en español.",
                callerName != null ? callerName : "Usuario"
        );
    }
}
