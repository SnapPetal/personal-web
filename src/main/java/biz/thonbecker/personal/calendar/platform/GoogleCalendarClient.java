package biz.thonbecker.personal.calendar.platform;

import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "calendar.google.enabled", havingValue = "true")
class GoogleCalendarClient {
    private static final String CLIENT_SECRET = "personal-web/google-calendar-oauth-client";
    private static final String AUTH_SECRET = "personal-web/google-calendar-authorization";
    private static final String API = "https://www.googleapis.com/calendar/v3";
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private final SecretsManagerClient secrets;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    GoogleCalendarClient(final org.springframework.core.env.Environment env) {
        this(
                CalDavAppTokenProvider.createClient(
                        "us-east-1",
                        env.getProperty("PERSONAL_AWS_ACCESS_KEY_ID", "test"),
                        env.getProperty("PERSONAL_AWS_SECRET_ACCESS_KEY", "test")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    GoogleCalendarClient(final SecretsManagerClient secrets, final HttpClient http) {
        this.secrets = secrets;
        this.http = http;
    }

    @PreDestroy
    void close() {
        secrets.close();
    }

    record Session(String calendarId, String accessToken) {}

    Session session() {
        final var client = secret(CLIENT_SECRET).path("installed");
        final var authorization = secret(AUTH_SECRET);
        final var calendar = required(authorization, "calendar_id");
        final var form = "client_id=" + encode(required(client, "client_id"))
                + "&client_secret=" + encode(required(client, "client_secret"))
                + "&refresh_token=" + encode(required(authorization, "refresh_token"))
                + "&grant_type=refresh_token";
        final var request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        final var tokenResponse = send(request);
        requireSuccess(tokenResponse, "refresh authorization");
        final var session = new Session(calendar, required(json.readTree(tokenResponse.body()), "access_token"));
        final var calendarResponse = get(session, "/users/me/calendarList/" + encode(calendar));
        requireSuccess(calendarResponse, "verify calendar");
        final var calendarJson = json.readTree(calendarResponse.body());
        final var role = required(calendarJson, "accessRole");
        if (!role.equals("owner") && !role.equals("writer")) {
            throw new IllegalStateException("Google calendar is not writable");
        }
        return session;
    }

    String marker(final Session session) {
        return "google:" + digest(session.calendarId());
    }

    String eventId(final Long bookingId) {
        return "b" + digest("personal-web-booking:" + bookingId);
    }

    void create(final Session session, final BookingCreatedEvent booking) {
        final var description = new StringBuilder()
                .append("Booking Type: ")
                .append(booking.bookingTypeName())
                .append("\nAttendee: ")
                .append(booking.attendeeName())
                .append("\nEmail: ")
                .append(booking.attendeeEmail());
        if (booking.attendeePhone() != null && !booking.attendeePhone().isBlank()) {
            description.append("\nPhone: ").append(booking.attendeePhone());
        }
        if (booking.message() != null && !booking.message().isBlank()) {
            description.append("\n\nMessage:\n").append(booking.message());
        }
        final var body = new LinkedHashMap<String, Object>();
        body.put("id", eventId(booking.bookingId()));
        body.put("summary", booking.bookingTypeName());
        body.put("description", description.toString());
        body.put(
                "start",
                Map.of(
                        "dateTime",
                        booking.startTime().atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        "timeZone",
                        ZONE.getId()));
        body.put(
                "end",
                Map.of(
                        "dateTime",
                        booking.endTime().atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        "timeZone",
                        ZONE.getId()));
        body.put("attendees", List.of(Map.of("email", booking.attendeeEmail(), "displayName", booking.attendeeName())));
        body.put(
                "extendedProperties",
                Map.of(
                        "private",
                        Map.of("personalWebBookingId", booking.bookingId().toString())));
        final var response = send(request(session, events(session) + "?sendUpdates=none")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build());
        if (response.statusCode() == 409) {
            if (!exists(session, booking.bookingId())) {
                throw new IllegalStateException("Existing Google calendar event is cancelled");
            }
            return;
        }
        requireSuccess(response, "create event");
    }

    boolean exists(final Session session, final Long bookingId) {
        final var response = get(session, events(session) + "/" + eventId(bookingId));
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            return false;
        }
        requireSuccess(response, "read event");
        final var event = json.readTree(response.body());
        if ("cancelled".equals(event.path("status").asString())) {
            return false;
        }
        if (!bookingId
                .toString()
                .equals(event.path("extendedProperties")
                        .path("private")
                        .path("personalWebBookingId")
                        .asString())) {
            throw new IllegalStateException("Google calendar event does not belong to this booking");
        }
        return true;
    }

    void delete(final Session session, final Long bookingId) {
        if (!exists(session, bookingId)) {
            return;
        }
        final var response = send(request(session, events(session) + "/" + eventId(bookingId) + "?sendUpdates=none")
                .DELETE()
                .build());
        if (response.statusCode() != 404 && response.statusCode() != 410) {
            requireSuccess(response, "delete event");
        }
    }

    private JsonNode secret(final String name) {
        final var value = secrets.getSecretValue(
                        GetSecretValueRequest.builder().secretId(name).build())
                .secretString();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Google Calendar secret is empty: " + name);
        }
        return json.readTree(value);
    }

    private static String required(final JsonNode node, final String field) {
        final var value = node.path(field).asString();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Google Calendar credential missing " + field);
        }
        return value;
    }

    private static String encode(final String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String digest(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String events(final Session session) {
        return "/calendars/" + encode(session.calendarId()) + "/events";
    }

    private HttpRequest.Builder request(final Session session, final String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + session.accessToken());
    }

    private HttpResponse<String> get(final Session session, final String path) {
        return send(request(session, path).GET().build());
    }

    private HttpResponse<String> send(final HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException exception) {
            throw new IllegalStateException("Google Calendar request failed", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google Calendar request interrupted", exception);
        }
    }

    private static void requireSuccess(final HttpResponse<String> response, final String action) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Google Calendar could not " + action + " (HTTP " + response.statusCode() + ")");
        }
    }
}
