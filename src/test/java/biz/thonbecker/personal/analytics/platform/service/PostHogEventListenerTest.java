package biz.thonbecker.personal.analytics.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import biz.thonbecker.personal.analytics.api.PostHogEventNames;
import biz.thonbecker.personal.booking.api.BookingCancelledEvent;
import biz.thonbecker.personal.booking.api.BookingCreatedEvent;
import biz.thonbecker.personal.booking.api.BookingStartedEvent;
import biz.thonbecker.personal.shared.api.ServerExceptionReportedEvent;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalEventListener;

class PostHogEventListenerTest {

    private final PostHogAnalyticsService postHogAnalyticsService = Mockito.mock(PostHogAnalyticsService.class);
    private final PostHogEventListener listener = new PostHogEventListener(postHogAnalyticsService);

    @Test
    void bookingStartedStaysOutsideTheEventPublicationRegistry() throws NoSuchMethodException {
        final Method method =
                PostHogEventListener.class.getDeclaredMethod("onBookingStarted", BookingStartedEvent.class);

        assertThat(method.getAnnotation(EventListener.class)).isNotNull();
        assertThat(method.getAnnotation(ApplicationModuleListener.class)).isNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNull();
    }

    @Test
    void sharedAnonymousBookingStartIsNotCaptured() {
        listener.onBookingStarted(new BookingStartedEvent("booking-anonymous"));

        verify(postHogAnalyticsService, never()).capture(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void confirmedBookingUsesTheVisitorDistinctId() {
        final var start = LocalDateTime.of(2026, 10, 20, 11, 0);
        listener.onBookingCreated(new BookingCreatedEvent(
                8L,
                "CONFIRM1",
                "ada@example.com",
                "Ada",
                null,
                "Chat",
                start,
                start.plusMinutes(30),
                null,
                "visitor-9"));

        verify(postHogAnalyticsService)
                .capture(Mockito.eq("visitor-9"), Mockito.eq(PostHogEventNames.BOOKING_CONFIRMED), Mockito.anyMap());
    }

    @Test
    void confirmedBookingWithoutAVisitorStaysOnItsOwnBookingId() {
        final var start = LocalDateTime.of(2026, 10, 20, 11, 0);
        listener.onBookingCreated(new BookingCreatedEvent(
                8L, "CONFIRM1", "ada@example.com", "Ada", null, "Chat", start, start.plusMinutes(30), null, null));

        verify(postHogAnalyticsService)
                .capture(Mockito.eq("booking-8"), Mockito.eq(PostHogEventNames.BOOKING_CONFIRMED), Mockito.anyMap());
    }

    @Test
    void cancelledBookingUsesTheVisitorDistinctId() {
        final var start = LocalDateTime.of(2026, 10, 20, 11, 0);
        listener.onBookingCancelled(new BookingCancelledEvent(
                8L, "CONFIRM1", "ada@example.com", "Ada", "Chat", start, start.plusMinutes(30), "visitor-9"));

        verify(postHogAnalyticsService)
                .capture(Mockito.eq("visitor-9"), Mockito.eq(PostHogEventNames.BOOKING_CANCELLED), Mockito.anyMap());
    }

    @Test
    void serverExceptionIsCapturedForErrorTracking() {
        listener.onServerException(ServerExceptionReportedEvent.from(
                "calendar.booking-created", new IllegalStateException("Radicale rejected attendee@example.com")));

        verify(postHogAnalyticsService)
                .capture(Mockito.eq("server"), Mockito.eq("$exception"), Mockito.argThat(properties -> {
                    if (!"calendar.booking-created".equals(properties.get("source"))) {
                        return false;
                    }
                    if (!"error".equals(properties.get("$exception_level"))) {
                        return false;
                    }
                    if (!(properties.get("$exception_list") instanceof List<?> exceptions)
                            || exceptions.isEmpty()
                            || !(exceptions.getFirst() instanceof Map<?, ?> exception)) {
                        return false;
                    }
                    return exception.get("value").toString().contains("[redacted-email]")
                            && !exception.get("value").toString().contains("attendee@example.com")
                            && exception.get("stacktrace") instanceof Map<?, ?> stacktrace
                            && "raw".equals(stacktrace.get("type"));
                }));
    }

    @Test
    void capturePropertiesStayUntouchedForALinkedStart() {
        listener.onBookingStarted(new BookingStartedEvent("visitor-1"));

        verify(postHogAnalyticsService)
                .capture(Mockito.eq("visitor-1"), Mockito.eq(PostHogEventNames.BOOKING_STARTED), Mockito.eq(Map.of()));
    }
}
