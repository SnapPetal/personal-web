package biz.thonbecker.personal.calendar.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "calendar.caldav")
record CalendarProperties(
        boolean enabled,
        String baseUrl,
        String calendarUrl,
        String username,
        String secretId,
        String secretJsonField,
        String region,
        String organizerEmail) {}
