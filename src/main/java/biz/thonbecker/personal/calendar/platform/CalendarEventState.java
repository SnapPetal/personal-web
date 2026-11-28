package biz.thonbecker.personal.calendar.platform;

/**
 * What Google Calendar reports for one mapped booking event.
 */
enum CalendarEventState {
    /** The event exists and is not cancelled. */
    ACTIVE,

    /** Google reports the event as deleted. Only this state may cancel a booking. */
    DELETED,

    /** Google has no record of the event. This is not proof that someone deleted it. */
    MISSING
}
