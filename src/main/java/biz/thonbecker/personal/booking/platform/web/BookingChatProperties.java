package biz.thonbecker.personal.booking.platform.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the public booking assistant. Defaults stay on a small Bedrock model. */
@Getter
@Setter
@ConfigurationProperties(prefix = "booking.chat")
public class BookingChatProperties {

    /** Bedrock inference profile or model id. Override with PERSONAL_BEDROCK_BOOKING_CHAT_MODEL. */
    private String model = "us.anthropic.claude-haiku-4-5-20251001-v1:0";

    private double temperature = 0.2;

    private int maxTokens = 1024;

    private int maxMessageLength = 1000;

    private int maxTimezoneLength = 100;

    private int maxRequestsPerWindow = 10;

    private Duration window = Duration.ofSeconds(60);

    private List<String> allowedOrigins = new ArrayList<>(
            List.of("https://thonbecker.biz", "https://www.thonbecker.biz", "https://booking.thonbecker.biz"));
}
