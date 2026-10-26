package biz.thonbecker.personal.booking.platform.web.model;

import java.util.List;

/** Read-only availability for the public booking page and its assistant. */
public record PublicAvailabilityResponse(String timezone, List<PublicAvailabilitySlot> slots) {}
