package biz.thonbecker.personal.booking.platform.web;

import biz.thonbecker.personal.booking.platform.web.model.BookingChatAnswer;

/** Result of admitting a public booking chat request. */
record BookingChatOutcome(int status, String error, BookingChatAnswer answer) {

    static BookingChatOutcome ok(final BookingChatAnswer answer) {
        return new BookingChatOutcome(200, null, answer);
    }

    static BookingChatOutcome error(final int status, final String error) {
        return new BookingChatOutcome(status, error, null);
    }
}
