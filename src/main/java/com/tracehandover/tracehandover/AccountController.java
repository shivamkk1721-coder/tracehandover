package com.tracehandover.tracehandover;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AccountController {
    private final TraceRepository repository;
    private final PasswordEncoder passwords;

    public AccountController(TraceRepository repository, PasswordEncoder passwords) {
        this.repository = repository;
        this.passwords = passwords;
    }

    static String normalizeUsername(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    @GetMapping("/login")
    String login() { return "login"; }

    @GetMapping("/register")
    String registration() { return "register"; }

    @PostMapping("/register")
    String register(@RequestParam(required = false) String username,
                    @RequestParam(required = false) String password,
                    @RequestParam(required = false) String confirmPassword,
                    Model model) {
        String normalized = normalizeUsername(username);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,31}")) {
            model.addAttribute("error", "Use 3–32 letters, numbers, dots, underscores or hyphens; start with a letter or number.");
            return "register";
        }
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            model.addAttribute("error", "Use a password with at least 12 characters and at most 72 UTF-8 bytes.");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match.");
            return "register";
        }
        try {
            repository.createAccount(normalized, passwords.encode(password));
        } catch (DataIntegrityViolationException e) {
            model.addAttribute("error", "That username is already in use. Choose another.");
            return "register";
        }
        return "redirect:/login?registered";
    }
}
