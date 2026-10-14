package biz.thonbecker.personal.booking.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Recognizes uptime monitors, load-balancer health checks, and other non-interactive clients.
 *
 * <p>Analytics must ignore these calls. The booking page itself stays available to them.
 */
@Component
class AutomatedProbeDetector {

    private static final List<String> PROBE_MARKERS = List.of(
            "upptime",
            "uptimerobot",
            "pingdom",
            "statuscake",
            "betteruptime",
            "better stack",
            "checkly",
            "datadog",
            "synthetics",
            "kube-probe",
            "elb-healthchecker",
            "amazon-route53",
            "amazon route 53",
            "lightsail",
            "googlehc",
            "cloudflare-healthcheck",
            "cloudflare-traffic-manager",
            "site24x7",
            "newrelicpinger",
            "hetrixtools",
            "ohdear",
            "montastic",
            "freshping",
            "nodeping",
            "go-http-client",
            "healthcheck",
            "uptime");

    boolean isAutomatedProbe(final HttpServletRequest request) {
        final var userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        final var normalized = userAgent.toLowerCase(Locale.ROOT);
        for (final var marker : PROBE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
