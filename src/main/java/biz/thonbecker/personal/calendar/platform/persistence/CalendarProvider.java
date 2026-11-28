package biz.thonbecker.personal.calendar.platform.persistence;

/**
 * Calendar that holds the event for a booking mapping.
 */
public enum CalendarProvider {
    /** Retired OpenCloud CalDAV collection. These mappings are kept but never synced. */
    OPENCLOUD,

    /** Google Calendar named Booking. */
    GOOGLE
}
