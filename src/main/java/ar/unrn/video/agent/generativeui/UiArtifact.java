package ar.unrn.video.agent.generativeui;

import java.util.List;

/**
 * A validated block of structured data travelling beside the assistant's prose, in its own field of
 * the HTTP payload rather than inside the text.
 *
 * @param kind  discriminator the frontend switches on ({@code "movies"} today, {@code "socios"}
 *              next); it is the fence name, so a new domain is a new kind and never a new regex
 * @param items already parsed and validated; element type depends on {@code kind}
 */
public record UiArtifact(String kind, List<Object> items) {
}
