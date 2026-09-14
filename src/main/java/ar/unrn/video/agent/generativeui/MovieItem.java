package ar.unrn.video.agent.generativeui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * One movie card in a {@code movies} Generative UI artifact.
 *
 * <p>The model is asked to emit {@code "price": "150.00"} as a JSON string, while the frontend
 * declares {@code price?: number}. Declaring it here as {@link BigDecimal} makes Jackson normalize
 * the string during deserialization, so the artifact that leaves this service always carries a
 * number and the frontend type stops lying.
 *
 * <p>Unknown properties are ignored on purpose: a model that invents an extra field must not fail
 * the whole extraction.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovieItem(
        Long id,
        String title,
        String genre,
        BigDecimal price,
        String imageUrl) {

    /**
     * A card with no identity or no title cannot be rendered or navigated to, so it is dropped
     * rather than shipped. Every element is checked, not just the first one.
     */
    public boolean isRenderable() {
        return id != null && title != null && !title.isBlank();
    }
}
