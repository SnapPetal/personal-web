package biz.thonbecker.personal.calendar.platform;

/**
 * OAuth client and refresh token that let the application act on the owner's Google Calendar.
 */
record GoogleCalendarCredentials(String clientId, String clientSecret, String refreshToken) {}
