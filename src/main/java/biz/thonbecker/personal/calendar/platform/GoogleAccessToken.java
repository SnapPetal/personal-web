package biz.thonbecker.personal.calendar.platform;

import java.time.Instant;

/**
 * Short-lived Google OAuth access token and the instant after which it must be refreshed.
 */
record GoogleAccessToken(String value, Instant refreshAfter) {}
