package ar.unrn.video.agent.config;

import ar.unrn.video.agent.generativeui.MovieItem;
import ar.unrn.video.agent.generativeui.UiArtifact;
import ar.unrn.video.agent.orchestrator.OrchestratorTools;
import ar.unrn.video.agent.rest.AgentController;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Reflection hints required by the GraalVM native image.
 *
 * <p>Spring AI discovers tools by scanning an object's methods for {@code @Tool} at
 * runtime. GraalVM compiles under a closed-world assumption: any member reached only
 * through reflection must be declared at build time or it simply is not there. Without
 * these hints the class loads fine but its methods are invisible, and the agent fails
 * with "No @Tool annotated methods found in ...".
 *
 * <p>This is a no-op on the JVM, where reflection needs no registration.
 */
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        // Tool discovery. INVOKE_DECLARED_METHODS covers both halves of the problem: the
        // scan that looks for the annotation, and the later reflective call to the method.
        hints.reflection().registerType(OrchestratorTools.class,
                MemberCategory.INVOKE_DECLARED_METHODS);

        // Generative UI payloads. These records are bound by Jackson from a call the
        // framework cannot see: GenerativeUiExtractor reads the model's ```json:movies
        // block through an ObjectMapper of its own, so Spring's AOT never learns the types
        // the way it does for a controller signature.
        //
        // Records need more than method access. Their component accessors must be in the
        // configuration or GraalVM refuses outright:
        //   "Record components not available for record class ... MovieItem"
        // BindingReflectionHintsRegistrar handles that, plus constructors, fields and
        // nested types, which a hand-written registerType would have to enumerate.
        // Every record that crosses the JSON boundary, registered in one place rather than
        // one GraalVM error at a time.
        //
        // ChatResponse deserves a note. Spring's AOT does register types it can read off a
        // handler signature, which is why ChatRequest arrives here already covered as a
        // @RequestBody parameter. ChatResponse does not: the handler is declared
        //
        //     public ResponseEntity<?> chat(...)
        //
        // and that wildcard erases the return type, so ChatResponse appears nowhere in a
        // signature -- only inside the method body, where static analysis cannot reach it.
        // The wildcard is deliberate (the same handler also returns Map error bodies), so
        // the hint is the fix, not a narrower return type.
        bindingRegistrar.registerReflectionHints(hints.reflection(),
                MovieItem.class,
                UiArtifact.class,
                AgentController.ChatRequest.class,
                AgentController.ChatResponse.class);

        // OpenTelemetry / Protobuf reflection hint.
        // com.google.protobuf.ExtensionRegistry.getEmptyRegistry() is invoked reflectively
        // by ExtensionRegistryFactory when protobuf classes are loaded.
        hints.reflection().registerType(
                TypeReference.of("com.google.protobuf.ExtensionRegistry"),
                MemberCategory.INVOKE_PUBLIC_METHODS);

        registerOpenAiModelHints(hints);
    }

    @SuppressWarnings("deprecation")
    private void registerOpenAiModelHints(final RuntimeHints hints) {
        // OpenAI Java SDK (Stainless) reflection hints for GraalVM Native Image.
        // Models in the official com.openai:openai-java SDK use
        // `@JsonAnySetter private final void putAdditionalProperty(String, JsonValue)`
        // to capture unknown/extra fields from OpenAI or compatible endpoints (e.g. Ollama returning
        // 'reasoning' on messages or 'index' on tool calls). GraalVM Native Image blocks reflective
        // invocation of private methods unless INVOKE_DECLARED_METHODS is registered.
        final Class<?>[] openAiModels = {
            com.openai.models.chat.completions.ChatCompletion.class,
            com.openai.models.chat.completions.ChatCompletion.Choice.class,
            com.openai.models.chat.completions.ChatCompletion.Choice.Logprobs.class,
            com.openai.models.chat.completions.ChatCompletionMessage.class,
            com.openai.models.chat.completions.ChatCompletionMessage.FunctionCall.class,
            com.openai.models.chat.completions.ChatCompletionMessage.Annotation.class,
            com.openai.models.chat.completions.ChatCompletionMessageToolCall.class,
            com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.class,
            com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.Function.class,
            com.openai.models.chat.completions.ChatCompletionMessageCustomToolCall.class,
            com.openai.models.chat.completions.ChatCompletionChunk.class,
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.class,
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.class,
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.FunctionCall.class,
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall.class,
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall.Function.class,
            com.openai.models.completions.CompletionUsage.class,
            com.openai.models.completions.CompletionUsage.PromptTokensDetails.class,
            com.openai.models.completions.CompletionUsage.CompletionTokensDetails.class,
            com.openai.core.JsonValue.class,
            com.openai.core.JsonField.class,
            com.openai.core.JsonObject.class,
            com.openai.core.JsonArray.class,
            com.openai.core.JsonString.class,
            com.openai.core.JsonNumber.class,
            com.openai.core.JsonBoolean.class,
            com.openai.core.JsonNull.class,
            com.openai.core.JsonMissing.class
        };

        for (final Class<?> clazz : openAiModels) {
            hints.reflection().registerType(clazz,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }
    }
}
