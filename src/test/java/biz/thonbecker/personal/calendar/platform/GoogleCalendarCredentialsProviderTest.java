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

class GoogleCalendarCredentialsProviderTest {

    @Test
    void readsOauthClientAndRefreshTokenFromSecretJson() {
        final var client = secretClient(
                "{\"client_id\":\"id\",\"client_secret\":\"secret\",\"refresh_token\":\"refresh\",\"other\":\"x\"}");
        final var provider = new GoogleCalendarCredentialsProvider(properties(), client);

        assertThat(provider.credentials()).isEqualTo(new GoogleCalendarCredentials("id", "secret", "refresh"));

        final var captor = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(client).getSecretValue(captor.capture());
        assertThat(captor.getValue().secretId()).isEqualTo("google/booking-calendar-oauth");
    }

    @Test
    void rejectsSecretWithoutRefreshToken() {
        final var provider = new GoogleCalendarCredentialsProvider(
                properties(), secretClient("{\"client_id\":\"id\",\"client_secret\":\"secret\"}"));

        assertThatThrownBy(provider::credentials)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh_token");
    }

    @Test
    void rejectsBlankAndInvalidSecrets() {
        assertThatThrownBy(() -> new GoogleCalendarCredentialsProvider(properties(), secretClient(" ")).credentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no string value");
        assertThatThrownBy(() ->
                        new GoogleCalendarCredentialsProvider(properties(), secretClient("not-json")).credentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void defaultsBlankRegionToUsEast1() {
        assertThat(GoogleCalendarCredentialsProvider.resolveRegion(null)).isEqualTo("us-east-1");
        assertThat(GoogleCalendarCredentialsProvider.resolveRegion(" ")).isEqualTo("us-east-1");
        assertThat(GoogleCalendarCredentialsProvider.resolveRegion("us-west-2")).isEqualTo("us-west-2");
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
        return new CalendarProperties(true, "Booking", "google/booking-calendar-oauth", "us-east-1");
    }
}
