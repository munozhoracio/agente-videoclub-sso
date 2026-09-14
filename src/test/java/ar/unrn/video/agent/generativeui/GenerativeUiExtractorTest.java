package ar.unrn.video.agent.generativeui;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test below pins one failure the browser-side parser had. The names say which.
 */
class GenerativeUiExtractorTest {

    private final GenerativeUiExtractor extractor = new GenerativeUiExtractor(new JsonMapper());

    @Test
    @DisplayName("extracts a movies block and leaves clean prose behind")
    void extractsMoviesBlock() {
        final String raw = """
                Te comparto las películas disponibles:
                ```json:movies
                [{"id": 10029, "title": "Matrix", "genre": "SCIENCE_FICTION", "price": "150.00", "imageUrl": "https://x/y.jpg"}]
                ```""";

        final GenerativeUiExtractor.ExtractionResult result = extractor.extract(raw);

        assertThat(result.text()).isEqualTo("Te comparto las películas disponibles:");
        assertThat(result.text()).doesNotContain("```");
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().getFirst().kind()).isEqualTo("movies");

        final MovieItem movie = (MovieItem) result.artifacts().getFirst().items().getFirst();
        assertThat(movie.id()).isEqualTo(10029L);
        assertThat(movie.title()).isEqualTo("Matrix");
    }

    @Test
    @DisplayName("normalizes the string price the prompt asks for into a number")
    void normalizesStringPrice() {
        final String raw = """
                Ahí va:
                ```json:movies
                [{"id": 1, "title": "Matrix", "price": "150.00"}]
                ```""";

        final MovieItem movie = (MovieItem) extractor.extract(raw)
                .artifacts().getFirst().items().getFirst();

        assertThat(movie.price()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("an empty array yields no artifact and still strips the fence")
    void emptyArrayDoesNotLeakTheFence() {
        final String raw = """
                No encontré películas con ese criterio.
                ```json:movies
                []
                ```""";

        final GenerativeUiExtractor.ExtractionResult result = extractor.extract(raw);

        assertThat(result.text()).isEqualTo("No encontré películas con ese criterio.");
        assertThat(result.text()).doesNotContain("json:movies");
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("a second block is consumed too, and json:socios never lands in the movies branch")
    void handlesMultipleBlocksAndDistinctKinds() {
        final String raw = """
                Esto encontré:
                ```json:movies
                [{"id": 1, "title": "Matrix"}]
                ```
                Y los socios:
                ```json:socios
                [{"id": 7, "nombre": "Ana"}]
                ```""";

        final GenerativeUiExtractor.ExtractionResult result = extractor.extract(raw);

        assertThat(result.text()).doesNotContain("```");
        assertThat(result.text()).doesNotContain("socios\n[");
        assertThat(result.text()).contains("Esto encontré:").contains("Y los socios:");

        // socios has no renderer yet, so only the movies artifact survives — but neither fence is shown.
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().getFirst().kind()).isEqualTo("movies");
    }

    @Test
    @DisplayName("malformed JSON is dropped, never rendered as broken text")
    void malformedJsonIsDropped() {
        final String raw = """
                Ahí va:
                ```json:movies
                [{"id": 1, "title": "Matrix",}
                ```""";

        final GenerativeUiExtractor.ExtractionResult result = extractor.extract(raw);

        assertThat(result.text()).isEqualTo("Ahí va:");
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("a bare ```json fence is still treated as movies")
    void bareJsonFenceDefaultsToMovies() {
        final String raw = """
                Ahí va:
                ```json
                [{"id": 1, "title": "Matrix"}]
                ```""";

        assertThat(extractor.extract(raw).artifacts()).hasSize(1);
    }

    @Test
    @DisplayName("every item is validated, not only the first one")
    void validatesEveryItem() {
        final String raw = """
                Ahí van:
                ```json:movies
                [{"id": 1, "title": "Matrix"}, {"id": 2}, {"title": "Sin id"}]
                ```""";

        final UiArtifact artifact = extractor.extract(raw).artifacts().getFirst();

        assertThat(artifact.items()).hasSize(1);
        assertThat(((MovieItem) artifact.items().getFirst()).title()).isEqualTo("Matrix");
    }

    @Test
    @DisplayName("strips redundant attribute bullets the prompt forbids")
    void stripsRedundantBullets() {
        final String raw = """
                Encontré esto:
                - Título: Matrix
                - Precio: 150.00
                ```json:movies
                [{"id": 1, "title": "Matrix"}]
                ```""";

        final String text = extractor.extract(raw).text();

        assertThat(text).isEqualTo("Encontré esto:");
    }

    @Test
    @DisplayName("plain conversational text passes through untouched")
    void plainTextIsUntouched() {
        final GenerativeUiExtractor.ExtractionResult result =
                extractor.extract("¡Hola! ¿En qué te puedo ayudar?");

        assertThat(result.text()).isEqualTo("¡Hola! ¿En qué te puedo ayudar?");
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("null and blank input do not blow up")
    void handlesNullAndBlank() {
        assertThat(extractor.extract(null).text()).isEmpty();
        assertThat(extractor.extract(null).artifacts()).isEmpty();
        assertThat(extractor.extract("   ").artifacts()).isEmpty();
    }
}
