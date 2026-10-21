package biz.thonbecker.personal.calendar.platform;

import jakarta.annotation.PreDestroy;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the CalDAV HTTP Basic password from AWS Secrets Manager.
 *
 * <p>The secret is a JSON object. The booking calendar uses the {@code booking_app_token} field of
 * {@code opencloud/calendar-app-tokens} in us-east-1.
 */
@Service
@ConditionalOnProperty(name = "calendar.caldav.enabled", havingValue = "true")
@Slf4j
class CalDavAppTokenProvider {

    private final CalendarProperties properties;
    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    CalDavAppTokenProvider(
            final CalendarProperties properties,
            @Value("${PERSONAL_AWS_ACCESS_KEY_ID:test}") final String accessKey,
            @Value("${PERSONAL_AWS_SECRET_ACCESS_KEY:test}") final String secretKey) {
        this(properties, createClient(properties.region(), accessKey, secretKey));
    }

    CalDavAppTokenProvider(final CalendarProperties properties, final SecretsManagerClient secretsManagerClient) {
        this.properties = properties;
        this.secretsManagerClient = secretsManagerClient;
    }

    @Retryable(retryFor = SdkException.class, maxAttempts = 3)
    public String bookingAppToken() {
        final var secretId = properties.secretId();
        final var response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder().secretId(secretId).build());
        final var token = readToken(objectMapper, response.secretString(), properties.secretJsonField());
        log.info("Loaded CalDAV app token from secret {}", secretId);
        return token;
    }

    @PreDestroy
    void close() {
        secretsManagerClient.close();
    }

    static SecretsManagerClient createClient(final String region, final String accessKey, final String secretKey) {
        return SecretsManagerClient.builder()
                .region(Region.of(resolveRegion(region)))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(normalizeCredential(accessKey), normalizeCredential(secretKey))))
                .build();
    }

    static String resolveRegion(final String region) {
        return Objects.isNull(region) || region.isBlank() ? "us-east-1" : region;
    }

    static String readToken(final ObjectMapper objectMapper, final String secretJson, final String fieldName) {
        if (Objects.isNull(secretJson) || secretJson.isBlank()) {
            throw new IllegalStateException("CalDAV secret has no string value");
        }
        if (Objects.isNull(fieldName) || fieldName.isBlank()) {
            throw new IllegalStateException("calendar.caldav.secret-json-field is required");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(secretJson);
        } catch (final RuntimeException exception) {
            throw new IllegalStateException("CalDAV secret is not valid JSON", exception);
        }

        final var field = root.get(fieldName);
        if (Objects.isNull(field) || field.isNull() || field.asText().isBlank()) {
            throw new IllegalStateException("CalDAV secret is missing JSON field " + fieldName);
        }
        return field.asText();
    }

    private static String normalizeCredential(final String value) {
        return Objects.nonNull(value) && !value.isBlank() ? value : "test";
    }
}
