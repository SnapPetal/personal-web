package biz.thonbecker.personal.foosball.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = FoosballController.class)
public class FoosballTenantModelAdvice {

    @ModelAttribute("tenantSlug")
    public String tenantSlug(HttpServletRequest request) {
        return (String) request.getAttribute("foosballTenantSlug");
    }
}
