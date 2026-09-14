package ar.unrn.video.agent.generativeui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lifts Generative UI blocks out of the assistant's text and turns them into validated
 * {@link UiArtifact}s.
 *
 * <h2>Why this runs on the server</h2>
 *
 * <p>The model emits structured data as a fenced block inside its prose, which means the UI contract
 * shares a channel with free text and the model can get it wrong. Parsing that block in the browser
 * made every failure mode visible to the user: an empty result leaked a raw {@code ```json:movies}
 * fence onto the screen, a second block was left untouched because the regex was not global, and a
 * {@code json:socios} block was swallowed by the movies branch and shown as broken JSON.
 *
 * <p>Doing it here removes that whole class of bug by establishing one invariant:
 *
 * <blockquote><b>Every recognized fence is always removed from the text.</b> Whether it parses,
 * whether it validates, whether its kind is even supported — it never reaches the user as
 * text.</blockquote>
 *
 * <p>An artifact is produced only when the block parses <em>and</em> yields at least one renderable
 * item. Anything else is dropped with a warning: a gap here is a server-side problem to fix, not
 * something to render. Those warnings are the signal that the model is drifting from the contract,
 * so they are logged loudly rather than swallowed.
 */
@Component
public class GenerativeUiExtractor {

    private static final Logger log = LoggerFactory.getLogger(GenerativeUiExtractor.class);

    /**
     * Matches ```` ```json:kind ```` and bare ```` ```json ````.
     *
     * <p>Group 1 captures the kind explicitly, which is what makes {@code json:socios} impossible to
     * confuse with {@code json:movies}. The previous browser-side expression alternated
     * {@code (?:json:movies|json)}, so {@code json:socios} matched via the bare {@code json} branch
     * and captured {@code ":socios\n[...]"} as its payload.
     *
     * <p>A bare {@code ```json} fence has no kind and is treated as {@link #DEFAULT_KIND}, matching
     * what the frontend tolerated before. That fallback is deliberate rather than accidental: models
     * do drop the suffix.
     */
    private static final Pattern FENCE = Pattern.compile(
            "```json(?::([A-Za-z][A-Za-z0-9_-]*))?[ \\t]*\\r?\\n?([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_KIND = "movies";
    private static final String MOVIES_KIND = "movies";

    /**
     * Bullets repeating what a card already shows. The prompt forbids them; this is the net for when
     * the model does it anyway. It used to live in the browser, which meant non-web clients never
     * got the benefit.
     */
    private static final Pattern REDUNDANT_ATTRIBUTE_BULLET = Pattern.compile(
            "^[-*•]\\s*(\\*\\*)?(título|title|género|genre|precio|price|imagen|image|id|código)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern STANDALONE_MARKDOWN_IMAGE = Pattern.compile(
            "^[-*•]?\\s*!?\\[.*?]\\(.*?\\)$");

    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    /**
     * Spring Boot 4 auto-configures Jackson 3's {@link JsonMapper}; there is no
     * {@code com.fasterxml.jackson.databind.ObjectMapper} bean in the context, even though Jackson 2
     * is still on the classpath transitively.
     */
    private final JsonMapper jsonMapper;

    public GenerativeUiExtractor(final JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * The assistant text with every fence removed, plus whatever artifacts survived validation.
     *
     * @param text      safe to show to any client, including a terminal
     * @param artifacts never null; empty when the turn carried no structured data
     */
    public record ExtractionResult(String text, List<UiArtifact> artifacts) {
    }

    /**
     * Extracts every fenced block in {@code rawResponse}, in the order the model emitted them.
     *
     * <p>Multiple blocks are supported because the orchestrator prompt explicitly allows consulting
     * both sub-agents and consolidating one answer; a single-match parser contradicted that rule.
     */
    public ExtractionResult extract(final String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new ExtractionResult(rawResponse == null ? "" : rawResponse, List.of());
        }

        final List<UiArtifact> artifacts = new ArrayList<>();
        final Matcher matcher = FENCE.matcher(rawResponse);
        final StringBuilder strippedText = new StringBuilder();

        while (matcher.find()) {
            final String kind = matcher.group(1) != null ? matcher.group(1).toLowerCase() : DEFAULT_KIND;
            final String payload = matcher.group(2) == null ? "" : matcher.group(2).trim();

            toArtifact(kind, payload).ifPresentOrElse(
                    artifacts::add,
                    () -> log.warn("Dropped a '{}' Generative UI block: it produced no renderable item. "
                            + "The block was still removed from the assistant text.", kind));

            // The fence leaves the text on every path — this is the invariant.
            matcher.appendReplacement(strippedText, "");
        }
        matcher.appendTail(strippedText);

        return new ExtractionResult(cleanUp(strippedText.toString()), List.copyOf(artifacts));
    }

    private Optional<UiArtifact> toArtifact(final String kind, final String payload) {
        if (!MOVIES_KIND.equals(kind)) {
            log.warn("Unsupported Generative UI kind '{}'. Add a branch here and a component in the "
                    + "frontend; until then the block is discarded rather than shown as raw JSON.", kind);
            return Optional.empty();
        }

        final List<MovieItem> parsed;
        try {
            parsed = jsonMapper.readValue(payload, new TypeReference<List<MovieItem>>() {
            });
        } catch (Exception e) {
            log.warn("Malformed JSON in a '{}' block: {}", kind, e.getMessage());
            return Optional.empty();
        }

        if (parsed == null) {
            return Optional.empty();
        }

        // Every element is validated, not just the first one.
        final List<Object> renderable = parsed.stream()
                .filter(item -> item != null && item.isRenderable())
                .map(Object.class::cast)
                .toList();

        if (renderable.size() < parsed.size()) {
            log.warn("Discarded {} of {} items in a '{}' block for missing id or title",
                    parsed.size() - renderable.size(), parsed.size(), kind);
        }

        // An empty array is the "nothing found" path. It yields no artifact, and because the fence
        // was already removed the user simply reads the assistant's sentence.
        return renderable.isEmpty() ? Optional.empty()
                : Optional.of(new UiArtifact(kind, renderable));
    }

    private String cleanUp(final String text) {
        final String withoutRedundantLines = Arrays.stream(text.split("\n", -1))
                .filter(line -> {
                    final String trimmed = line.trim();
                    return !REDUNDANT_ATTRIBUTE_BULLET.matcher(trimmed).find()
                            && !STANDALONE_MARKDOWN_IMAGE.matcher(trimmed).matches();
                })
                .collect(Collectors.joining("\n"));

        return EXCESS_BLANK_LINES.matcher(withoutRedundantLines).replaceAll("\n\n").trim();
    }
}
