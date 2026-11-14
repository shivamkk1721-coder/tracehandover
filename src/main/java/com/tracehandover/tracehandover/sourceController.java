package com.tracehandover.tracehandover;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class sourceController {
    private final GeminiService geminiService;

    public sourceController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/upload")
    public String upload(
            @RequestParam("files") List<MultipartFile> files,
            Model model,
            HttpSession session)  {

        List<Map<String, String>> passages = new ArrayList<>();
        int fileNumber = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            String filename = file.getOriginalFilename();
            if (filename == null) {
                filename = "";
            }

            String lowerName = filename.toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".txt")
                    && !lowerName.endsWith(".md")
                    && !lowerName.endsWith(".csv")) {
                model.addAttribute(
                        "error", "Please upload only .txt, .md or .csv files.");
                return "index";
            }

            fileNumber++;

            try {
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                String[] lines = content.split("\\R", -1);

                for (int i = 0; i < lines.length; i++) {
                    if (!lines[i].isBlank()) {
                        passages.add(Map.of(
                                "id", "F" + fileNumber + "-L" + (i + 1),
                                "file", filename,
                                "text", lines[i]
                        ));
                    }
                }
            } catch (IOException e) {
                model.addAttribute("error", "Could not read a file. Please try again.");
                return "index";
            }
        }

        if (passages.isEmpty()) {
            model.addAttribute("error", "Please choose files containing some text.");
        } else {
            model.addAttribute("passages", passages);
            session.setAttribute("passages", passages);
            session.removeAttribute("suggestions");

            try {
                Map<String, Map<String, String>> suggestions =
                        geminiService.suggest(passages);

                model.addAttribute("suggestions", suggestions);
                session.setAttribute("suggestions", suggestions);

                if (suggestions.isEmpty()) {
                    model.addAttribute("aiMessage",
                            "Gemini found no suggestions. You can review manually.");
                } else {
                    model.addAttribute("aiMessage",
                            "Gemini suggestions are ready. Check them against the sources.");
                }

            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                String detail = e.getMessage();

                if (detail == null || !(
                        detail.matches("Gemini request failed: HTTP [0-9]{3}")
                                || detail.equals("Gemini API key is not configured.")
                                || detail.equals("Use 40 or fewer source passages.")
                                || detail.equals("Source text is too long for this demo.")
                )) {
                    detail = "Check the connection, API quota, and input size.";
                }

                model.addAttribute("aiMessage",
                        "Gemini is unavailable: " + detail
                                + " You can still review manually.");
            }
        }

        return "index";
    }
}