package biz.thonbecker.personal.shared;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThemeContrastTest {

    @Test
    void bodyMutedLinksAndBadgesClearWcagAa() {
        assertAtLeast(4.5, "#212529", "#ffffff");
        assertAtLeast(4.5, "#212529", "#f8f9fa");
        assertAtLeast(4.5, "#656e76", "#ffffff");
        assertAtLeast(4.5, "#656e76", "#f8f9fa");
        assertAtLeast(4.5, "#0d69fa", "#ffffff");
        assertAtLeast(4.5, "#0d69fa", "#f8f9fa");
        assertAtLeast(4.5, "#f8f9fa", "#212529");
        assertAtLeast(4.5, "#f8f9fa", "#2b3035");
        assertAtLeast(4.5, "#ced4da", "#212529");
        assertAtLeast(4.5, "#ced4da", "#2b3035");
        assertAtLeast(4.5, "#8bb9fe", "#212529");
        assertAtLeast(4.5, "#8bb9fe", "#2b3035");
        assertAtLeast(4.5, "#e7f1ff", "#212529");
        assertAtLeast(4.5, "#212529", "#8bb9fe");
        assertAtLeast(4.5, "#ffffff", "#0d6efd");
        assertAtLeast(4.5, "#ffffff", "#198754");
        assertAtLeast(4.5, "#ffffff", "#dc3545");
        assertAtLeast(4.5, "#212529", "#ffc107");
        assertAtLeast(4.5, "#212529", "#0dcaf0");
        assertAtLeast(4.5, "#212529", "#17a2b8");
        assertAtLeast(4.5, "#75d69c", "#212529");
        assertAtLeast(4.5, "#ff8a95", "#212529");
        assertAtLeast(4.5, "#946800", "#ffffff");
        assertAtLeast(4.5, "#f0c674", "#212529");
        assertAtLeast(4.5, "#ffffff", "#1f3f77");
    }

    private static void assertAtLeast(final double minimum, final String foreground, final String background) {
        final var ratio = contrast(foreground, background);
        assertTrue(ratio >= minimum, () -> foreground + " on " + background + " is " + ratio);
    }

    private static double contrast(final String foreground, final String background) {
        final var lighter = Math.max(luminance(foreground), luminance(background));
        final var darker = Math.min(luminance(foreground), luminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(final String hex) {
        final var value = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((value >> 16) & 0xff)
                + 0.7152 * channel((value >> 8) & 0xff)
                + 0.0722 * channel(value & 0xff);
    }

    private static double channel(final int component) {
        final var amount = component / 255.0;
        return amount <= 0.04045 ? amount / 12.92 : Math.pow((amount + 0.055) / 1.055, 2.4);
    }
}
