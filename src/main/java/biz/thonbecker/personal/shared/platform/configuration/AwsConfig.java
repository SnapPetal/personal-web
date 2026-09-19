package biz.thonbecker.personal.shared.platform.configuration;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.ses.SesClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Configuration for AWS SDK clients.
 * Provides configured clients for Polly (text-to-speech) and S3 (storage).
 */
@Configuration
public class AwsConfig {

    @Value("${PERSONAL_AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${PERSONAL_AWS_ACCESS_KEY_ID:test}")
    private String accessKey;

    @Value("${PERSONAL_AWS_SECRET_ACCESS_KEY:test}")
    private String secretKey;

    private StaticCredentialsProvider credentialsProvider() {
        final var resolvedAccessKey = Objects.nonNull(accessKey) && !accessKey.isBlank() ? accessKey : "test";
        final var resolvedSecretKey = Objects.nonNull(secretKey) && !secretKey.isBlank() ? secretKey : "test";
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(resolvedAccessKey, resolvedSecretKey));
    }

    private Region region() {
        return Region.of(Objects.nonNull(awsRegion) && !awsRegion.isBlank() ? awsRegion : "us-east-1");
    }

    /**
     * Creates a configured PollyClient bean for text-to-speech conversion.
     *
     * @return Configured PollyClient
     */
    @Bean
    public PollyClient pollyClient() {
        return PollyClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * Creates a configured S3Client bean for audio file storage.
     *
     * @return Configured S3Client
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public MediaConvertClient mediaConvertClient() {
        return MediaConvertClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * Creates a configured BedrockRuntimeClient bean for Bedrock invocation.
     *
     * @return Configured BedrockRuntimeClient
     */
    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * Creates a configured SesClient bean for sending emails.
     *
     * @return Configured SesClient
     */
    @Bean
    public SesClient sesClient() {
        return SesClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * Provides a Jackson ObjectMapper bean for components that need direct JSON parsing.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Creates a configured S3VectorsClient bean for vector store operations.
     *
     * @return Configured S3VectorsClient
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "skatetricks.vectorstore.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public S3VectorsClient s3VectorsClient() {
        return S3VectorsClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider())
                .build();
    }
}
