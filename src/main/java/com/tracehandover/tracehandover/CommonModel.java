package com.tracehandover.tracehandover;

import java.security.Principal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CommonModel {
    @ModelAttribute("currentUsername")
    String currentUsername(Principal principal) { return principal == null ? null : principal.getName(); }
}
