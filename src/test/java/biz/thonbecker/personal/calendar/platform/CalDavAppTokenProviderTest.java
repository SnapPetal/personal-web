package biz.thonbecker.personal.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

class CalDavAppTokenProviderTest {

    @Test
    void readsBookingAppTokenFromSecretJson() {
        final var client = Mockito.mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString("{\"booking_app_token\":\"app-token\",\"other\":\"unused\"}")
                        .build());
        final var provider = new CalDavAppTokenProvider(properties(), client);

        assertThat(provider.bookingAppToken()).isEqualTo("app-token");

        final var captor = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(client).getSecretValue(captor.capture());
        assertThat(captor.getValue().secretId()).isEqualTo("opencloud/calendar-app-tokens");
    }

    @Test
    void rejectsSecretWithoutBookingAppToken() {
        final var client = secretClient("{\"other\":\"x\"}");
        final var provider = new CalDavAppTokenProvider(properties(), client);

        assertThatThrownBy(provider::bookingAppToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("booking_app_token");
    }

    @Test
    void rejectsBlankAndInvalidSecrets() {
        assertThatThrownBy(() -> new CalDavAppTokenProvider(properties(), secretClient(" ")).bookingAppToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no string value");
        assertThatThrownBy(() -> new CalDavAppTokenProvider(properties(), secretClient("not-json")).bookingAppToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void defaultsBlankRegionToUsEast1() {
        assertThat(CalDavAppTokenProvider.resolveRegion(null)).isEqualTo("us-east-1");
        assertThat(CalDavAppTokenProvider.resolveRegion(" ")).isEqualTo("us-east-1");
        assertThat(CalDavAppTokenProvider.resolveRegion("us-east-1")).isEqualTo("us-east-1");
    }

    private static SecretsManagerClient secretClient(final String secretJson) {
        final var client = Mockito.mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString(secretJson)
                        .build());
        return client;
    }

    private static CalendarProperties properties() {
        return new CalendarProperties(
                true,
                "https://files.thonbecker.biz/caldav/",
                "https://files.thonbecker.biz/caldav/eced1100-d041-41c1-b707-1ec59bc1a016/bookings/",
                "thonbecker",
                "opencloud/calendar-app-tokens",
                "booking_app_token",
                "us-east-1",
                "org@example.com");
    }
}
