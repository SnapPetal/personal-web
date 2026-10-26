package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingChatSlot;
import java.util.List;

/** Model output before it is limited to openings that actually exist. */
record BookingChatDraft(String message, String action, List<BookingChatSlot> availableSlots) {}
