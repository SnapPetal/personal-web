package biz.thonbecker.personal.htmx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HtmxTemplateTest {

    private static final Path TEMPLATE_ROOT = Path.of("src/main/resources/templates");

    @Test
    void templatesUseHtmxFourSyntax() throws IOException {
        final var templates = readTemplates();
        final var allTemplates = templates.collect(Collectors.joining("\n"));

        assertFalse(allTemplates.contains("hx-disabled-elt"));
        assertFalse(allTemplates.contains("htmx:afterRequest"));
        assertTrue(allTemplates.contains("<hx-partial"));
        assertTrue(allTemplates.contains("hx-status:422"));
        assertTrue(allTemplates.contains("hx-sync=\"this:abort\""));
        assertTrue(allTemplates.contains("hx-get=\"/booking/types/0/slots\""));
        assertFalse(allTemplates.contains("/booking/slots"));
        assertTrue(allTemplates.contains("/booking/types/${this.selectedBookingTypeId}/slots"));
        assertTrue(
                allTemplates.contains("<th:block th:if=\"${error != null and (slots == null or slots.isEmpty())}\">"));
        assertFalse(allTemplates.contains("hx-boost:inherited=\"true\""));
    }

    @Test
    void sharedHtmxAssetsIncludePerformanceExtensions() throws IOException {
        final var assets = Files.readString(TEMPLATE_ROOT.resolve("fragments/assets.html"));

        assertTrue(assets.contains("hx-preload.min.js"));
        assertTrue(assets.contains("hx-history-cache.min.js"));
    }

    private static Stream<String> readTemplates() throws IOException {
        try (var paths = Files.walk(TEMPLATE_ROOT)) {
            return paths
                    .filter(path -> path.toString().endsWith(".html"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (final IOException e) {
                            throw new IllegalStateException("Failed to read " + path, e);
                        }
                    })
                    .toList()
                    .stream();
        }
    }
}
