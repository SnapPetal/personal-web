package biz.thonbecker.personal.budget.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class BudgetController {

    @GetMapping("/budget")
    String budgetHome(final Model model, final HttpServletResponse response) {
        model.addAttribute("conversationId", UUID.randomUUID());
        response.setHeader("Cache-Control", "no-store");
        return "budget/index";
    }
}
