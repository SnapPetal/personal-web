package biz.thonbecker.personal.booking.platform.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BookingChatProperties.class)
class BookingChatConfig {}
