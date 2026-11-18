package com.tracehandover.tracehandover;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ReviewController {
    private static final Set<String> STATUSES = Set.of("Unknown", "Not started", "In progress", "Completed", "Blocked");
    private static final int MAX_PASSAGES = 40;
    private static final int MAX_TEXT_CHARS = 16_000;
    private final GeminiService gemini;
    private final ProjectPermissions permissions;
    private final HandoverStore store;

    record Draft(Long projectId, String token, List<EvidenceReconciler.Group> groups, String aiMessage) {}
    public record ReviewGroup(EvidenceReconciler.Group group, String task, String owner, String status,
                              String next, boolean selected, boolean notesSelected,
                              Map<String, List<String>> selections) {
        public List<String> evidence(String field) { return selections.getOrDefault(field, List.of()); }
    }

    public ReviewController(GeminiService gemini, ProjectPermissions permissions, HandoverStore store) {
        this.gemini = gemini;
        this.permissions = permissions;
        this.store = store;
    }

    @PostMapping("/demo")
    String demo(@RequestParam(defaultValue = "related") String grouping, HttpSession session, Model model) {
        List<EvidenceReconciler.Source> sources = List.of(
                source("F1-L1", "meeting-notes.txt", "Collect remaining recordings: Asha collected some clips before leaving."),
                source("F1-L2", "meeting-notes.txt", "Collect remaining recordings: the team still needs the missing clips."),
                source("F2-L1", "task-list.csv", "Task,Owner,Status,Next step"),
                source("F2-L2", "task-list.csv", "Collect remaining recordings,Unassigned,In progress,Find missing clips"),
                source("F2-L3", "task-list.csv", "Collect remaining recordings,Unassigned,Completed,Find missing clips"),
                source("F2-L4", "task-list.csv", "Check recording lengths,Rahul,In progress,Review each clip"),
                source("F2-L5", "task-list.csv", "Combine final presentation,Unassigned,Not started,Wait for all recordings")
        );
        return prepare(null, sources, !"separate".equals(grouping), false, session, model);
    }

    @PostMapping("/projects/{projectId}/upload")
    String upload(@PathVariable long projectId,
                  @RequestParam(value = "files", required = false) List<MultipartFile> files,
                  @RequestParam(defaultValue = "related") String grouping,
                  Principal principal, HttpSession session, Model model, RedirectAttributes flash) {
        var project = permissions.coordinator(projectId, principal);
        try {
            List<EvidenceReconciler.Source> sources = parseFiles(files);
            model.addAttribute("project", project);
            return prepare(projectId, sources, !"separate".equals(grouping), true, session, model);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/" + projectId;
        } catch (IOException e) {
            flash.addFlashAttribute("error", "The files could not be read. Please try again.");
            return "redirect:/projects/" + projectId;
        }
    }

    private List<EvidenceReconciler.Source> parseFiles(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty() || files.size() > 10)
            throw new IllegalArgumentException("Choose one to ten .txt, .md or .csv files.");
        List<EvidenceReconciler.Source> sources = new ArrayList<>();
        int fileNumber = 0;
        int totalCharacters = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String filename = safeFilename(file.getOriginalFilename());
            String lower = filename.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".txt") && !lower.endsWith(".md") && !lower.endsWith(".csv"))
                throw new IllegalArgumentException("Only .txt, .md and .csv files are supported.");
            if (file.getSize() > 1_000_000) throw new IllegalArgumentException("Each file must be under 1 MB.");
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Files must contain UTF-8 text.");
            }
            totalCharacters += content.length();
            if (totalCharacters > MAX_TEXT_CHARS)
                throw new IllegalArgumentException("Use at most 16,000 characters across your files.");
            fileNumber++;
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank()) sources.add(source("F" + fileNumber + "-L" + (i + 1), filename, lines[i]));
                if (sources.size() > MAX_PASSAGES)
                    throw new IllegalArgumentException("Use at most 40 non-empty source lines.");
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("Choose at least one file containing text.");
        return sources;
    }

    private String safeFilename(String raw) {
        String filename = raw == null ? "" : raw.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 120) throw new IllegalArgumentException("Use a file name up to 120 characters.");
        return filename;
    }

    private EvidenceReconciler.Source source(String id, String file, String text) {
        return new EvidenceReconciler.Source(id, file, text);
    }

    private String prepare(Long projectId, List<EvidenceReconciler.Source> sources,
                           boolean groupRelated, boolean useAi, HttpSession session, Model model) {
        List<Map<String, String>> passages = sources.stream()
                .map(s -> Map.of("id", s.id(), "file", s.file(), "text", s.text())).toList();
        Map<String, Map<String, String>> suggestions = Map.of();
        String aiMessage;
        if (useAi) {
            try {
                suggestions = gemini.suggest(passages);
                aiMessage = suggestions.isEmpty() ? "No AI suggestions were returned. You can still review manually."
                        : "Suggestions are ready. Check each claim and source before approval.";
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                aiMessage = "AI suggestions are unavailable. You can still review the sources manually.";
            }
        } else {
            aiMessage = "This built-in sample includes missing ownership and conflicting statuses for you to review.";
        }
        var groups = EvidenceReconciler.reconcile(sources, suggestions, groupRelated);
        if (groups.isEmpty()) throw new IllegalArgumentException("No reviewable source lines were found.");
        String token = UUID.randomUUID().toString();
        var draft = new Draft(projectId, token, groups, aiMessage);
        session.setAttribute(sessionKey(projectId), draft);
        return renderDraft(draft, null, model);
    }

    private String renderDraft(Draft draft, MultiValueMap<String, String> submitted, Model model) {
        model.addAttribute("reviewGroups", draft.groups().stream().map(group -> reviewGroup(group, submitted)).toList());
        model.addAttribute("requestToken", draft.token());
        model.addAttribute("projectId", draft.projectId());
        model.addAttribute("aiMessage", draft.aiMessage());
        return "review";
    }

    private ReviewGroup reviewGroup(EvidenceReconciler.Group group, MultiValueMap<String, String> submitted) {
        String task = entered(submitted, "task_" + group.id(), singleValue(group, "task", group.title()));
        String owner = entered(submitted, "owner_" + group.id(), singleValue(group, "owner", ""));
        String status = entered(submitted, "status_" + group.id(), singleValue(group, "status", "Unknown"));
        if (!STATUSES.contains(status)) status = "Unknown";
        String next = entered(submitted, "next_" + group.id(), singleValue(group, "next", ""));
        boolean selected = submitted != null && submitted.getOrDefault("approved", List.of()).contains(group.id());
        boolean notesSelected = submitted != null && submitted.getOrDefault("includeNotes", List.of()).contains(group.id());
        Map<String, List<String>> selections = new LinkedHashMap<>();
        if (submitted != null) for (String field : List.of("task", "owner", "status", "next"))
            selections.put(field, submitted.getOrDefault("evidence_" + field + "_" + group.id(), List.of()));
        return new ReviewGroup(group, task, owner, status, next, selected, notesSelected, selections);
    }

    private String entered(MultiValueMap<String, String> submitted, String key, String fallback) {
        String value = submitted != null && submitted.containsKey(key) ? submitted.getFirst(key) : fallback;
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private String singleValue(EvidenceReconciler.Group group, String field, String fallback) {
        var values = group.claims().get(field);
        return values.size() == 1 ? values.getFirst().value() : fallback;
    }

    @PostMapping("/demo/approve")
    String approveDemo(@RequestParam MultiValueMap<String, String> form, HttpSession session,
                       Model model, RedirectAttributes flash) {
        try {
            var snapshot = parseReview(null, form, session);
            session.setAttribute("demoResult", snapshot);
            return "redirect:/demo/result";
        } catch (IllegalArgumentException e) {
            Draft draft = currentDraft(null, form, session);
            if (draft != null) {
                model.addAttribute("error", e.getMessage());
                return renderDraft(draft, form, model);
            }
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
    }

    @GetMapping("/demo/result")
    String demoResult(HttpSession session, Model model, RedirectAttributes flash) {
        Object result = session.getAttribute("demoResult");
        if (!(result instanceof HandoverSnapshot snapshot)) {
            flash.addFlashAttribute("error", "Start the sample project first.");
            return "redirect:/";
        }
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("demo", true);
        return "version";
    }

    @PostMapping("/projects/{projectId}/approve")
    String approveProject(@PathVariable long projectId, @RequestParam MultiValueMap<String, String> form,
                          Principal principal, HttpSession session, Model model, RedirectAttributes flash) {
        permissions.coordinator(projectId, principal);
        try {
            var snapshot = parseReview(projectId, form, session);
            long versionId = store.approve(projectId, principal, form.getFirst("requestToken"), snapshot);
            return "redirect:/projects/" + projectId + "/versions/" + versionId;
        } catch (IllegalArgumentException e) {
            Draft draft = currentDraft(projectId, form, session);
            if (draft != null) {
                model.addAttribute("error", e.getMessage());
                return renderDraft(draft, form, model);
            }
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/" + projectId;
        }
    }

    private Draft currentDraft(Long projectId, MultiValueMap<String, String> form, HttpSession session) {
        Object stored = session.getAttribute(sessionKey(projectId));
        return stored instanceof Draft draft && draft.token().equals(form.getFirst("requestToken")) ? draft : null;
    }

    private HandoverSnapshot parseReview(Long projectId, MultiValueMap<String, String> form, HttpSession session) {
        Object stored = session.getAttribute(sessionKey(projectId));
        if (!(stored instanceof Draft draft) || !draft.token().equals(form.getFirst("requestToken")))
            throw new IllegalArgumentException("Your review session changed or expired. Upload the sources again.");
        Set<String> selected = new LinkedHashSet<>(form.getOrDefault("approved", List.of()));
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one item to include.");
        Set<String> allowed = new LinkedHashSet<>();
        draft.groups().forEach(g -> allowed.add(g.id()));
        if (!allowed.containsAll(selected)) throw new IllegalArgumentException("A selected item was not in your review.");
        List<HandoverSnapshot.Item> items = new ArrayList<>();
        for (var group : draft.groups()) {
            if (!selected.contains(group.id())) continue;
            var task = claim(group, "task", form, true);
            var owner = claim(group, "owner", form, false);
            var status = claim(group, "status", form, false);
            var next = claim(group, "next", form, false);
            if (!status.value().isBlank() && !STATUSES.contains(status.value()))
                throw new IllegalArgumentException("Choose a valid status.");
            boolean includeNotes = form.getOrDefault("includeNotes", List.of()).contains(group.id());
            items.add(new HandoverSnapshot.Item(task, owner, status, next,
                    includeNotes ? group.warnings() : List.of()));
        }
        return new HandoverSnapshot(items);
    }

    private HandoverSnapshot.Claim claim(EvidenceReconciler.Group group, String field,
                                         MultiValueMap<String, String> form, boolean required) {
        String raw = form.getFirst(field + "_" + group.id());
        String value = raw == null ? "" : raw.strip();
        if ((required && value.isBlank()) || value.length() > 300)
            throw new IllegalArgumentException("Each selected task needs a title; each field can contain up to 300 characters.");
        Map<String, EvidenceReconciler.Source> available = new LinkedHashMap<>();
        group.sources().forEach(source -> available.put(source.id(), source));
        Set<String> requested = new LinkedHashSet<>(form.getOrDefault("evidence_" + field + "_" + group.id(), List.of()));
        if (!available.keySet().containsAll(requested)) throw new IllegalArgumentException("An evidence source was not in the reviewed group.");
        List<HandoverSnapshot.SourceRef> references = value.isBlank() ? List.of() : requested.stream().map(id -> {
            var source = available.get(id);
            return new HandoverSnapshot.SourceRef(source.id(), source.file(), source.text());
        }).toList();
        return new HandoverSnapshot.Claim(value, references);
    }

    private String sessionKey(Long projectId) { return projectId == null ? "review:demo" : "review:project:" + projectId; }
}
