package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GoogleCalendarConfigurationTest {

    @Test
    void productionConfigUsesGoogleCalendarNamedBooking() throws Exception {
        final var yaml = resource("/application.yml");

        assertThat(yaml).contains("calendar-name: Booking");
        assertThat(yaml).contains("secret-id: google/booking-calendar-oauth");
        assertThat(yaml).doesNotContain("caldav");
        assertThat(yaml).doesNotContain("opencloud");
        assertThat(yaml).doesNotContain("files.thonbecker.biz");
    }

    @Test
    void devAndTestProfilesDisableGoogleCalendar() throws Exception {
        assertThat(resource("/application-dev.yml")).contains("google:").doesNotContain("caldav");
        final var testYaml = new String(
                GoogleCalendarConfigurationTest.class
                        .getClassLoader()
                        .getResourceAsStream("application-test.yml")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(testYaml).contains("google:").doesNotContain("caldav");
    }

    private static String resource(final String path) throws Exception {
        try (var input = GoogleCalendarConfigurationTest.class.getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
