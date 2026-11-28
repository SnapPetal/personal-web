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
 * Loads the Google OAuth client and refresh token from AWS Secrets Manager.
 *
 * <p>The secret is a JSON object with the fields {@code client_id}, {@code client_secret}, and
 * {@code refresh_token}.
 */
@Service
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
@Slf4j
class GoogleCalendarCredentialsProvider {

    private final CalendarProperties properties;
    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    GoogleCalendarCredentialsProvider(
            final CalendarProperties properties,
            @Value("${PERSONAL_AWS_ACCESS_KEY_ID:test}") final String accessKey,
            @Value("${PERSONAL_AWS_SECRET_ACCESS_KEY:test}") final String secretKey) {
        this(properties, createClient(properties.region(), accessKey, secretKey));
    }

    GoogleCalendarCredentialsProvider(
            final CalendarProperties properties, final SecretsManagerClient secretsManagerClient) {
        this.properties = properties;
        this.secretsManagerClient = secretsManagerClient;
    }

    @Retryable(retryFor = SdkException.class, maxAttempts = 3)
    public GoogleCalendarCredentials credentials() {
        final var secretId = properties.secretId();
        if (Objects.isNull(secretId) || secretId.isBlank()) {
            throw new IllegalStateException("calendar.google.secret-id is required");
        }
        final var response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder().secretId(secretId).build());
        final var credentials = readCredentials(objectMapper, response.secretString());
        log.info("Loaded Google Calendar credentials from secret {}", secretId);
        return credentials;
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

    static GoogleCalendarCredentials readCredentials(final ObjectMapper objectMapper, final String secretJson) {
        if (Objects.isNull(secretJson) || secretJson.isBlank()) {
            throw new IllegalStateException("Google Calendar secret has no string value");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(secretJson);
        } catch (final RuntimeException exception) {
            throw new IllegalStateException("Google Calendar secret is not valid JSON", exception);
        }

        return new GoogleCalendarCredentials(
                requiredField(root, "client_id"),
                requiredField(root, "client_secret"),
                requiredField(root, "refresh_token"));
    }

    private static String requiredField(final JsonNode root, final String fieldName) {
        final var field = root.get(fieldName);
        if (Objects.isNull(field) || field.isNull() || field.asText().isBlank()) {
            throw new IllegalStateException("Google Calendar secret is missing JSON field " + fieldName);
        }
        return field.asText();
    }

    private static String normalizeCredential(final String value) {
        return Objects.nonNull(value) && !value.isBlank() ? value : "test";
    }
}
