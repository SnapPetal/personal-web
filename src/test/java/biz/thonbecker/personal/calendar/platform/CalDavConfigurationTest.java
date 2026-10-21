package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CalDavConfigurationTest {

    @Test
    void productionConfigUsesOpenCloudCollectionAndSecret() throws Exception {
        final var yaml = resource("/application.yml");

        assertThat(yaml).contains("base-url: https://files.thonbecker.biz/caldav/");
        assertThat(yaml)
                .contains(
                        "calendar-url: https://files.thonbecker.biz/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/");
        assertThat(yaml).contains("username: thonbecker");
        assertThat(yaml).contains("secret-id: opencloud/calendar-app-tokens");
        assertThat(yaml).contains("secret-json-field: booking_app_token");
        assertThat(yaml).contains("region: us-east-1");
        assertThat(yaml.toLowerCase()).doesNotContain("nextcloud");
        assertThat(yaml).doesNotContain("remote.php");
        assertThat(yaml).doesNotContain("PERSONAL_NEXTCLOUD");
        assertThat(yaml).doesNotContain("cloud.thonbecker.biz");
    }

    @Test
    void devAndTestProfilesDisableCalDav() throws Exception {
        assertThat(resource("/application-dev.yml")).contains("caldav:").doesNotContain("nextcloud");
        final var testYaml = new String(
                CalDavConfigurationTest.class
                        .getClassLoader()
                        .getResourceAsStream("application-test.yml")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(testYaml).contains("caldav:").doesNotContain("nextcloud");
    }

    private static String resource(final String path) throws Exception {
        try (var input = CalDavConfigurationTest.class.getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
