package com.petcare.enterprise.shared.infrastructure.web.landing;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring MVC controller that renders the public institutional landing page for PetCare Enterprise
 * using Thymeleaf, completely isolated from the clinical SPA shell and database persistence.
 */
@Controller
public class PublicLandingController {

    @GetMapping("/presentacion")
    public String renderPresentationLanding(Model model) {
        model.addAttribute("valueProposition", PublicLandingViewModel.valueProposition());
        model.addAttribute("productPhase", PublicLandingViewModel.productPhase());
        model.addAttribute("navigationLinks", PublicLandingViewModel.navigationLinks());
        model.addAttribute("benefits", PublicLandingViewModel.benefits());
        model.addAttribute("implementedModules", PublicLandingViewModel.implementedModules());
        model.addAttribute("upcomingModules", PublicLandingViewModel.upcomingModules());
        model.addAttribute("technologyStack", PublicLandingViewModel.technologyStack());
        return "presentacion/index";
    }

    @GetMapping("/presentacion/")
    public String redirectCanonicalPresentationLanding() {
        return "redirect:/presentacion";
    }
}
