package biz.thonbecker.personal.foosball.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps the original Foosball entry point working while tenant URLs are adopted. */
@Controller
public class FoosballLegacyRedirectController {

    @GetMapping("/foosball")
    public String redirectToDefaultTenant() {
        return "redirect:/foosball/ramsey-solutions";
    }
}
