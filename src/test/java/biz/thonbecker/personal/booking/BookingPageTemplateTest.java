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
    void recordsBookingStartFromTheMeetingTypeChoice() throws IOException {
        final var template = Files.readString(BOOKING_TEMPLATE);

        assertTrue(template.contains("fetch('/booking/started'"));
        assertTrue(template.contains("recordBookingStarted()"));
        assertFalse(template.contains("booking-anonymous"));
    }

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

    @Test
    void appStylesDeclareSelfHostedNotoFontsOnABookingHostPath() throws IOException {
        final var theme = Files.readString(Path.of("src/main/resources/static/css/theme.css"));
        final var assets = Files.readString(Path.of("src/main/resources/templates/fragments/assets.html"));

        assertTrue(theme.contains("@font-face"));
        assertTrue(theme.contains("font-family: \"Noto Sans\""));
        assertTrue(theme.contains("font-family: \"Noto Serif\""));
        assertTrue(theme.contains("--bs-font-sans-serif: \"Noto Sans\""));
        assertTrue(theme.contains("--bs-body-font-family: var(--bs-font-sans-serif)"));
        assertTrue(theme.contains("font-family: var(--bs-body-font-family)"));
        assertTrue(theme.contains("url(\"/css/fonts/noto-sans-latin.woff2\")"));
        assertTrue(theme.contains("url(\"/css/fonts/noto-serif-greek.woff2\")"));
        assertFalse(theme.contains("url(\"/fonts/"));
        assertTrue(assets.contains("/css/fonts/noto-sans-latin.woff2"));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/static/css/fonts/noto-sans-latin.woff2")));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/static/css/fonts/noto-serif-latin.woff2")));
    }

    @Test
    void bookingHeaderMatchesThePublicSiteHeader() throws IOException {
        final var navigation = Files.readString(BOOKING_NAVIGATION);
        final var template = Files.readString(BOOKING_TEMPLATE);
        final var styles = Files.readString(BOOKING_STYLES);
        final var theme = Files.readString(Path.of("src/main/resources/static/css/theme.css"));
        final var sharedHeader = Files.readString(Path.of("design-system/src/main/resources/core/site-header.css"));
        final var designSystem = Files.readString(Path.of("design-system/src/main/resources/design-system.css"));
        final var homepage = Files.readString(Path.of("static-site/index.html"));

        assertTrue(navigation.contains("class=\"site-header\""));
        assertTrue(navigation.contains("class=\"brand\""));
        assertTrue(navigation.contains("class=\"brand-mark brand-mark-light\""));
        assertTrue(navigation.contains("class=\"brand-mark brand-mark-dark\""));
        assertTrue(navigation.contains("data-menu-toggle"));
        assertTrue(navigation.contains("id=\"primary-navigation\""));
        assertTrue(navigation.contains("class=\"site-nav-link\""));
        assertFalse(navigation.contains("booking-site-header"));
        assertFalse(navigation.contains("booking-brand"));
        assertFalse(navigation.contains("navbar-toggler"));
        assertTrue(template.contains("class=\"page-title\""));
        assertTrue(homepage.contains("class=\"site-header\""));
        assertTrue(homepage.contains("class=\"page-title\""));
        assertTrue(homepage.contains("class=\"site-nav-link\""));
        assertFalse(homepage.contains("button compact"));
        assertTrue(designSystem.contains("site-header.css"));
        assertTrue(sharedHeader.contains("color: var(--text-primary);"));
        assertTrue(sharedHeader.contains("color: #ffffff;"));
        assertTrue(sharedHeader.contains("font-size: clamp(2.75rem, 5vw, 4.35rem);"));
        assertTrue(sharedHeader.contains("text-decoration: underline;"));
        assertFalse(sharedHeader.contains("--app-link-color"));
        assertFalse(styles.contains("booking-primary-nav"));
        assertFalse(styles.contains(".booking-hero h1"));
        assertTrue(theme.contains(":not(.site-nav-link)"));
        assertTrue(Files.readString(Path.of(".github/workflows/deploy.yml")).contains("site/site-header.css"));
    }
}
