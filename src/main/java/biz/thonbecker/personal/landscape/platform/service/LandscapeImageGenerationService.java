package biz.thonbecker.personal.landscape.platform.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for generating landscape images using Amazon Nova Canvas via AWS Bedrock.
 */
@Service
@Slf4j
public class LandscapeImageGenerationService {

    private static final String DEFAULT_MODEL_ID = "amazon.nova-canvas-v1:0";
    private static final int IMAGE_WIDTH = 1024;
    private static final int IMAGE_HEIGHT = 1024;
    private static final int MAX_PIXELS = 4_194_304;

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final String modelId;

    @Autowired
    public LandscapeImageGenerationService(
            @Nullable final BedrockRuntimeClient bedrockRuntimeClient,
            final ObjectMapper objectMapper,
            @Value("${landscape.image-generation.model:" + DEFAULT_MODEL_ID + "}") final String modelId) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.modelId = modelId;
    }

    /**
     * Generates a seasonal variation by editing the original landscape image using Nova Canvas.
     *
     * @param landscapeImageData Original landscape image bytes
     * @param season Season name (Spring, Summer, Fall, Winter)
     * @param placements Plant placement instructions with normalized image coordinates
     * @return Base64-encoded generated image, or null if generation fails
     */
    public String generateSeasonalImage(
            final byte[] landscapeImageData, final String season, final List<PlantPlacementPrompt> placements) {

        try {
            if (Objects.isNull(bedrockRuntimeClient)) {
                log.warn(
                        "Skipping {} landscape image generation because BedrockRuntimeClient is not configured",
                        season);
                return null;
            }

            log.info("Generating {} landscape image with Nova Canvas", season);

            final var resizedData = resizeIfNeeded(landscapeImageData);
            final var base64Image = Base64.getEncoder().encodeToString(resizedData);
            final var prompt = buildSeasonalEditPrompt(season, placements);

            final var requestBody = Map.of(
                    "taskType",
                    "TEXT_IMAGE",
                    "textToImageParams",
                    Map.of(
                            "text",
                            prompt,
                            "conditionImage",
                            base64Image,
                            "controlMode",
                            "CANNY_EDGE",
                            "controlStrength",
                            0.8),
                    "imageGenerationConfig",
                    Map.of("width", IMAGE_WIDTH, "height", IMAGE_HEIGHT, "quality", "standard", "numberOfImages", 1));

            final var jsonBody = objectMapper.writeValueAsString(requestBody);

            final var request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(jsonBody))
                    .build();

            final var response = bedrockRuntimeClient.invokeModel(request);
            final var responseJson = response.body().asUtf8String();

            final var rootNode = objectMapper.readTree(responseJson);

            if (rootNode.has("error")) {
                log.warn(
                        "Nova Canvas returned error for {} image: {}",
                        season,
                        rootNode.get("error").asText());
                return null;
            }

            final var images = rootNode.path("images");
            if (images.isArray() && !images.isEmpty()) {
                final var generatedImage = images.get(0).asText();
                if (Objects.nonNull(generatedImage) && !generatedImage.isBlank()) {
                    log.info("Successfully generated {} landscape image", season);
                    return generatedImage;
                }
            }

            log.warn("Nova Canvas returned no base64 image for {}", season);
            return null;

        } catch (final Exception e) {
            log.error("Failed to generate {} landscape image: {}", season, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resizes the image if it exceeds Nova Canvas's 4,194,304 pixel limit.
     */
    private byte[] resizeIfNeeded(final byte[] imageData) {
        try {
            final var original = ImageIO.read(new ByteArrayInputStream(imageData));
            if (Objects.isNull(original)) {
                return imageData;
            }

            final long pixels = (long) original.getWidth() * original.getHeight();
            if (pixels <= MAX_PIXELS) {
                return imageData;
            }

            final var scaleFactor = Math.sqrt((double) MAX_PIXELS / pixels);
            final var newWidth = (int) (original.getWidth() * scaleFactor);
            final var newHeight = (int) (original.getHeight() * scaleFactor);

            log.info(
                    "Resizing image from {}x{} ({} pixels) to {}x{} for Nova Canvas",
                    original.getWidth(),
                    original.getHeight(),
                    pixels,
                    newWidth,
                    newHeight);

            final var resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            final var g = resized.createGraphics();
            g.drawImage(original, 0, 0, newWidth, newHeight, null);
            g.dispose();

            final var baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", baos);
            return baos.toByteArray();

        } catch (final Exception e) {
            log.warn("Failed to resize image, sending original: {}", e.getMessage());
            return imageData;
        }
    }

    private String buildSeasonalEditPrompt(final String season, final List<PlantPlacementPrompt> placements) {
        final var plantInstructions = placements.isEmpty()
                ? "- No new plants are placed; only adjust the existing yard for the season."
                : placements.stream()
                        .map(placement -> String.format(
                                "- Add %s (%s) at x=%.1f%% from the left edge and y=%.1f%% from the top edge. "
                                        + "Treat this coordinate as the trunk/root base location where the plant "
                                        + "emerges from the ground. Render the entire plant from ground contact to "
                                        + "full canopy or top growth, scaled and lit as if it is planted in this yard.",
                                placement.displayName(),
                                placement.usdaSymbol(),
                                placement.xPercent(),
                                placement.yPercent()))
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");

        return """
                Edit this exact uploaded residential yard photo into a single realistic %s landscape preview image.

                Preserve the original house, roofline, driveway, windows, street, camera angle, perspective, image
                composition, and property layout. Do not invent a different house or a different yard. Keep unchanged
                areas visually consistent with the input photo.

                Add the planned plants at these precise normalized image locations. For trees, the coordinate is the
                trunk base/root flare where the tree enters the ground, not the center of the canopy:
                %s

                Seasonal appearance instructions:
                %s

                Generate one cohesive image containing all of the listed plant placements together in the same yard.
                Make the result photorealistic. Render each newly added tree or plant as a full-size landscape element
                planted in the ground of the house/yard: visible trunk or stems, complete canopy/top growth, natural
                root contact, realistic scale, perspective, shadows, occlusion, and lighting. Do not render thumbnails,
                icons, stickers, circular plant photos, floating cutouts, labels, map pins, diagram markers, text, or
                UI elements.
                """.formatted(season, plantInstructions, seasonalStyle(season));
    }

    private String seasonalStyle(final String season) {
        return switch (season.toLowerCase()) {
            case "spring" ->
                "Early spring: fresh green grass, budding trees, soft new leaves, emerging flowers, clear natural "
                        + "daylight, and lightly refreshed planting beds.";
            case "summer" ->
                "Summer: full green lawn, mature full foliage, strong healthy leaves, flowering plants in bloom, and "
                        + "bright warm daylight.";
            case "fall" ->
                "Autumn: orange, gold, and red deciduous foliage where appropriate, some fallen leaves, slightly "
                        + "warmer afternoon light, and seasonal but tidy planting beds.";
            case "winter" ->
                "Winter: dormant grass, bare deciduous branches where appropriate, evergreens still green, subtle "
                        + "cold overcast light, and no heavy snow unless already present in the input image.";
            default -> "Natural realistic seasonal residential landscaping.";
        };
    }

    public record PlantPlacementPrompt(String usdaSymbol, String displayName, double xPercent, double yPercent) {}
}
