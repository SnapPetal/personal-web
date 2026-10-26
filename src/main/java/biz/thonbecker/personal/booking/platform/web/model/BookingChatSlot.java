package biz.thonbecker.personal.booking.platform.web.model;

/** One open meeting time the assistant is allowed to offer. */
public record BookingChatSlot(String start, String end, String timezone) {}
