package biz.thonbecker.personal.calendar.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "calendar.google")
record CalendarProperties(boolean enabled, String calendarName, String secretId, String region) {}
