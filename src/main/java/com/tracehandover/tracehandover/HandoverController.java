package com.tracehandover.tracehandover;

import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HandoverController {

    @PostMapping("/handover")
    public String makeHandover(
            @RequestParam(value = "approved", required = false)
            List<String> approved,
            @RequestParam Map<String, String> form,
            HttpSession session,
            Model model) {

        Object stored = session.getAttribute("passages");

        if (!(stored instanceof List<?>)) {
            model.addAttribute("error", "Please upload your files again.");
            return "index";
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> passages =
                (List<Map<String, String>>) stored;

        List<Map<String, String>> handover = new ArrayList<>();

        if (approved != null) {
            for (Map<String, String> passage : passages) {
                String id = passage.get("id");

                if (approved.contains(id)) {
                    handover.add(Map.of(
                            "id", id,
                            "file", passage.get("file"),
                            "source", passage.get("text"),
                            "task", valueOrUnknown(form.get("task_" + id)),
                            "owner", valueOrUnknown(form.get("owner_" + id)),
                            "status", valueOrUnknown(form.get("status_" + id)),
                            "next", valueOrUnknown(form.get("next_" + id))
                    ));
                }
            }
        }

        if (handover.isEmpty()) {
            model.addAttribute("error", "Select at least one item to include.");
            model.addAttribute("passages", passages);
        } else {
            model.addAttribute("handover", handover);
        }

        return "index";
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank()
                ? "Not specified"
                : value.trim();
    }
}