package com.tracehandover.tracehandover;

import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProjectController {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectController.class);
    private final TraceRepository repository;
    private final ProjectPermissions permissions;
    private final HandoverStore handovers;

    public ProjectController(TraceRepository repository, ProjectPermissions permissions, HandoverStore handovers) {
        this.repository = repository;
        this.permissions = permissions;
        this.handovers = handovers;
    }

    @GetMapping("/projects")
    String list(Principal principal, HttpSession session, Model model) {
        var account = permissions.account(principal);
        model.addAttribute("projects", repository.projectsFor(account.id()));
        synchronized (session) {
            if (session.getAttribute("projectCreateToken") == null)
                session.setAttribute("projectCreateToken", UUID.randomUUID().toString());
            model.addAttribute("projectCreateToken", session.getAttribute("projectCreateToken"));
        }
        return "projects";
    }

    @PostMapping("/projects")
    String create(@RequestParam(required = false) String name,
                  @RequestParam(required = false) String departingMember,
                  @RequestParam(required = false) String requestToken,
                  Principal principal, HttpSession session, RedirectAttributes flash) {
        var account = permissions.account(principal);
        String safeName = name == null ? "" : name.strip();
        String safeMember = departingMember == null ? "" : departingMember.strip();
        if (safeName.isBlank() || safeName.length() > 120 || safeMember.isBlank() || safeMember.length() > 120) {
            flash.addFlashAttribute("error", "Enter a project name and departing member, each up to 120 characters.");
            return "redirect:/projects";
        }
        long projectId;
        synchronized (session) {
            Object expected = session.getAttribute("projectCreateToken");
            if (expected == null || !expected.equals(requestToken)) {
                flash.addFlashAttribute("message", "This project form was already used. Refresh before creating another project.");
                return "redirect:/projects";
            }
            projectId = repository.createProject(safeName, safeMember, account.id());
            session.removeAttribute("projectCreateToken");
        }
        return "redirect:/projects/" + projectId;
    }

    @GetMapping("/projects/{projectId}")
    String project(@PathVariable long projectId, Principal principal, Model model) {
        var project = permissions.member(projectId, principal);
        model.addAttribute("project", project);
        model.addAttribute("versions", repository.versions(projectId));
        if ("COORDINATOR".equals(project.role()))
            model.addAttribute("recipients", repository.recipientNames(projectId));
        return "project";
    }

    @PostMapping("/projects/{projectId}/recipients")
    String addRecipient(@PathVariable long projectId,
                        @RequestParam(value = "recipientUsername", required = false) String username,
                        Principal principal, RedirectAttributes flash) {
        permissions.coordinator(projectId, principal);
        String normalized = AccountController.normalizeUsername(username);
        var recipient = repository.accountByUsername(normalized);
        if (recipient.isEmpty()) {
            flash.addFlashAttribute("error", "No account found. Ask the recipient to register first and check the username.");
            return "redirect:/projects/" + projectId;
        }
        var existingRole = repository.memberRole(projectId, recipient.get().id());
        if (existingRole.isPresent()) {
            flash.addFlashAttribute("message", alreadyMember(recipient.get().username(), existingRole.get()));
            return "redirect:/projects/" + projectId;
        }
        try {
            repository.addRecipient(projectId, recipient.get().id());
            flash.addFlashAttribute("message", "Recipient access added.");
        } catch (DataIntegrityViolationException e) {
            var roleAfterRetry = repository.memberRole(projectId, recipient.get().id());
            if (roleAfterRetry.isPresent()) {
                flash.addFlashAttribute("message", alreadyMember(recipient.get().username(), roleAfterRetry.get()));
            } else {
                LOG.warn("Could not add a recipient to project {}", projectId, e);
                flash.addFlashAttribute("error", "Could not add the recipient. Please try again or check the server logs.");
            }
        }
        return "redirect:/projects/" + projectId;
    }

    @PostMapping("/projects/{projectId}/recipients/remove")
    String removeRecipient(@PathVariable long projectId,
                           @RequestParam(value = "recipientUsername", required = false) String username,
                           Principal principal, RedirectAttributes flash) {
        permissions.coordinator(projectId, principal);
        var recipient = repository.accountByUsername(AccountController.normalizeUsername(username));
        if (recipient.isPresent() && repository.removeRecipient(projectId, recipient.get().id())) {
            flash.addFlashAttribute("message", "Recipient access removed.");
        } else {
            flash.addFlashAttribute("message", "That recipient no longer has access to this project.");
        }
        return "redirect:/projects/" + projectId;
    }

    private String alreadyMember(String username, String role) {
        return "Account '" + username + "' already has " + role.toLowerCase(Locale.ROOT) + " access to this project.";
    }

    @GetMapping("/projects/{projectId}/versions/{versionId}")
    String version(@PathVariable long projectId, @PathVariable long versionId,
                   Principal principal, Model model) {
        var project = permissions.member(projectId, principal);
        var version = repository.version(projectId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var acknowledgements = repository.acknowledgements(versionId);
        var account = permissions.account(principal);
        model.addAttribute("project", project);
        model.addAttribute("version", version);
        model.addAttribute("snapshot", handovers.readSnapshot(version.snapshotJson()));
        model.addAttribute("acknowledged", acknowledgements.stream().anyMatch(a -> a.username().equals(account.username())));
        if ("COORDINATOR".equals(project.role())) model.addAttribute("acknowledgements", acknowledgements);
        return "version";
    }

    @PostMapping("/projects/{projectId}/versions/{versionId}/acknowledge")
    String acknowledge(@PathVariable long projectId, @PathVariable long versionId, Principal principal) {
        permissions.recipient(projectId, principal);
        repository.version(projectId, versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repository.acknowledge(versionId, permissions.account(principal).id());
        return "redirect:/projects/" + projectId + "/versions/" + versionId;
    }
}
