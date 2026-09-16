package ar.unrn.video.agent.config;

import java.util.stream.Collectors;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * Enriches Spring AI OpenTelemetry spans with prompt and completion content
 * so that LangSmith can display them in the "Input" and "Output" UI tabs.
 *
 * <p>Spring AI by default avoids putting prompt/completion strings into span attributes
 * to prevent accidental PII leakage in standard APMs. LangSmith, however, expects
 * {@code gen_ai.prompt} / {@code inputs} and {@code gen_ai.completion} / {@code outputs}
 * attributes to render the conversational turns.
 */
@Configuration(proxyBeanMethods = false)
public class LangSmithObservationConfiguration {

    @Bean
    public ChatModelObservationConvention langSmithChatModelObservationConvention() {
        return new DefaultChatModelObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(final ChatModelObservationContext context) {
                KeyValues keyValues = super.getHighCardinalityKeyValues(context);

                final Prompt request = context.getRequest();
                if (request != null && request.getInstructions() != null) {
                    final String promptText = request.getInstructions().stream()
                            .map(m -> m.getMessageType() + ": " + m.getText())
                            .collect(Collectors.joining("\n---\n"));
                    keyValues = keyValues.and(KeyValue.of("gen_ai.prompt", promptText));
                    keyValues = keyValues.and(KeyValue.of("inputs", promptText));
                }

                final ChatResponse response = context.getResponse();
                if (response != null && response.getResult() != null) {
                    final Generation generation = response.getResult();
                    if (generation.getOutput() != null && generation.getOutput().getText() != null) {
                        final String outputText = generation.getOutput().getText();
                        keyValues = keyValues.and(KeyValue.of("gen_ai.completion", outputText));
                        keyValues = keyValues.and(KeyValue.of("outputs", outputText));
                    }
                }

                return keyValues;
            }
        };
    }

    @Bean
    public ChatClientObservationConvention langSmithChatClientObservationConvention() {
        return new DefaultChatClientObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(final ChatClientObservationContext context) {
                KeyValues keyValues = super.getHighCardinalityKeyValues(context);

                if (context.getRequest() != null && context.getRequest().prompt() != null) {
                    final String userText = context.getRequest().prompt().getContents();
                    keyValues = keyValues.and(KeyValue.of("gen_ai.prompt", userText));
                    keyValues = keyValues.and(KeyValue.of("inputs", userText));
                }

                if (context.getResponse() != null && context.getResponse().chatResponse() != null
                        && context.getResponse().chatResponse().getResult() != null
                        && context.getResponse().chatResponse().getResult().getOutput() != null
                        && context.getResponse().chatResponse().getResult().getOutput().getText() != null) {
                    final String outputText = context.getResponse().chatResponse().getResult().getOutput().getText();
                    keyValues = keyValues.and(KeyValue.of("gen_ai.completion", outputText));
                    keyValues = keyValues.and(KeyValue.of("outputs", outputText));
                }

                return keyValues;
            }
        };
    }
}
