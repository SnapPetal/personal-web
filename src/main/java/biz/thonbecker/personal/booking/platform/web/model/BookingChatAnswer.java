package biz.thonbecker.personal.booking.platform.web.model;

import java.util.List;

/**
 * Visitor-facing booking chat reply. {@code availableSlots} contains only openings that were open when
 * the reply was built.
 */
public record BookingChatAnswer(String message, String action, List<BookingChatSlot> availableSlots) {}
