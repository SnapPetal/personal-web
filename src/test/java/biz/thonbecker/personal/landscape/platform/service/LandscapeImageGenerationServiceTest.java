package biz.thonbecker.personal.landscape.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.ObjectMapper;

class LandscapeImageGenerationServiceTest {

    private static final String MODEL_ID = "us.stability.stable-image-control-structure-v1:0";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final byte[] PNG_IMAGE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};

    private BedrockRuntimeClient bedrockRuntimeClient;
    private LandscapeImageGenerationService service;

    @BeforeEach
    void setUp() {
        bedrockRuntimeClient = mock(BedrockRuntimeClient.class);
        service = new LandscapeImageGenerationService(bedrockRuntimeClient, OBJECT_MAPPER, MODEL_ID);
    }

    @Test
    void sendsStableImageControlStructureRequestAndExtractsGeneratedImage() throws Exception {
        final var responseJson = """
                {
                  "images": ["generated-base64-image"]
                }
                """;
        final var response = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(responseJson))
                .build();
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response);

        final var result = service.generateSeasonalImage(
                PNG_IMAGE,
                "Spring",
                List.of(new LandscapeImageGenerationService.PlantPlacementPrompt("ACRU", "Red maple", 34.5, 62.0)));

        assertEquals("generated-base64-image", result);

        final var captor = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockRuntimeClient).invokeModel(captor.capture());

        final var request = captor.getValue();
        assertEquals(MODEL_ID, request.modelId());
        assertEquals("application/json", request.contentType());
        assertEquals("application/json", request.accept());

        final var requestJson = OBJECT_MAPPER.readTree(request.body().asUtf8String());
        assertTrue(requestJson.path("prompt").asText().contains("Add Red maple (ACRU)"));
        assertTrue(requestJson.path("prompt").asText().contains("x=34.5%"));
        assertTrue(requestJson.path("prompt").asText().contains("y=62.0%"));
        assertEquals(0.55, requestJson.path("control_strength").asDouble());
        assertEquals("png", requestJson.path("output_format").asText());
        assertTrue(requestJson.path("image").asText().length() > 0);
    }

    @Test
    void skipsRequestWhenBedrockClientIsNull() {
        final var serviceWithoutClient = new LandscapeImageGenerationService(null, OBJECT_MAPPER, MODEL_ID);

        final var result = serviceWithoutClient.generateSeasonalImage(PNG_IMAGE, "Spring", List.of());

        assertNull(result);
    }

    @Test
    void returnsNullWhenResponseContainsError() {
        final var responseJson = """
                {
                  "error": "AccessDenied"
                }
                """;
        final var response = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(responseJson))
                .build();
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response);

        final var result = service.generateSeasonalImage(PNG_IMAGE, "Summer", List.of());

        assertNull(result);
    }

    @Test
    void returnsNullWhenResponseContainsNoImages() {
        final var responseJson = """
                {
                  "images": []
                }
                """;
        final var response = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(responseJson))
                .build();
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response);

        final var result = service.generateSeasonalImage(PNG_IMAGE, "Summer", List.of());

        assertNull(result);
    }

    @Test
    void returnsNullWhenGenerationIsFiltered() {
        final var responseJson = """
                {
                  "finish_reasons": ["CONTENT_FILTERED"],
                  "images": ["generated-base64-image"]
                }
                """;
        final var response = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(responseJson))
                .build();
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class))).thenReturn(response);

        final var result = service.generateSeasonalImage(PNG_IMAGE, "Winter", List.of());

        assertNull(result);
    }

    @Test
    void handlesExceptionGracefully() {
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(new RuntimeException("Bedrock unavailable"));

        final var result = service.generateSeasonalImage(PNG_IMAGE, "Winter", List.of());

        assertNull(result);
    }
}
