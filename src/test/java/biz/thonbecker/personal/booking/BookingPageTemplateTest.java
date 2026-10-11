package biz.thonbecker.personal.booking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BookingPageTemplateTest {

    private static final Path BOOKING_TEMPLATE = Path.of("src/main/resources/templates/booking/index.html");
    private static final Path BOOKING_NAVIGATION = Path.of("src/main/resources/templates/booking/fragments.html");
    private static final Path BOOKING_STYLES = Path.of("src/main/resources/static/css/pages/booking.css");

    @Test
    void keepsTheAssistantSeparateFromTheMeetingTypeSection() throws IOException {
        final var template = Files.readString(BOOKING_TEMPLATE);
        final var styles = Files.readString(BOOKING_STYLES);

        assertTrue(template.contains("class=\"public-chat-form\""));
        assertTrue(template.contains("class=\"input-group public-chat-input-group\""));
        assertTrue(styles.contains("margin-bottom: clamp(3rem, 6vw, 5rem);"));
        assertTrue(styles.contains("@media (max-width: 400px)"));
        assertTrue(styles.contains("grid-template-columns: 1fr;"));
    }

    @Test
    void followsTheSystemColorSchemeWithoutAStoredTheme() throws IOException {
        final var template = Files.readString(BOOKING_TEMPLATE);
        final var navigation = Files.readString(BOOKING_NAVIGATION);
        final var styles = Files.readString(BOOKING_STYLES);
        final var theme = Files.readString(Path.of("src/main/resources/static/css/theme.css"));

        assertFalse(template.contains("theme-toggle.js"));
        assertFalse(navigation.contains("themeToggle"));
        assertFalse(navigation.contains("booking-theme-toggle"));
        assertFalse(theme.contains("data-bs-theme"));
        assertTrue(styles.contains("@media (prefers-color-scheme: dark)"));
        assertTrue(styles.contains("color: #ffffff;"));
        assertTrue(theme.contains("@media (prefers-color-scheme: dark)"));
    }
}
