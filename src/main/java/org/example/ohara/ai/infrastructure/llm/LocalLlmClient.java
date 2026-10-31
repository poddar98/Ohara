package org.example.ohara.ai.infrastructure.llm;

import org.example.ohara.ai.application.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in LLM for development and tests. It reads the transcript lines out of the prompt ({@code [startMs-endMs] text})
 * and builds plausible, valid output from them: no model involved. Markers in the staff instructions change the
 * outcome: LLM_INVALID (bad JSON until asked to repair), LLM_BROKEN (always bad), LLM_REFUSE (refuses),
 * LLM_DOWN (provider unavailable).
 */
@Component
@ConditionalOnProperty(name = "ohara.ai.llm.provider", havingValue = "local", matchIfMissing = true)
class LocalLlmClient implements LlmClient {

    private static final Pattern EXCERPT = Pattern.compile("^(E\\d+) \\[(\\d+)-(\\d+)] (.+)$", Pattern.MULTILINE);
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+)-(\\d+)] (.+)$", Pattern.MULTILINE);

    private record Line(long startMs, long endMs, String text) {
    }

    private final JsonMapper json;

    LocalLlmClient(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String provider() {
        return "LOCAL";
    }

    @Override
    public Response complete(Request request) {
        String prompt = request.user();
        int in = (request.system().length() + prompt.length()) / 4;
        if (prompt.contains("LLM_DOWN")) {
            throw new Unavailable("Simulated provider outage");
        }
        if (prompt.contains("LLM_REFUSE")) {
            return new Response("", "local-llm-1", in, 5, 0, StopReason.REFUSAL);
        }
        boolean repair = prompt.contains("Your previous answer was invalid");
        if (prompt.contains("LLM_BROKEN") || (prompt.contains("LLM_INVALID") && !repair)) {
            return new Response("{\"oops\": ", "local-llm-1", in, 4, 0, StopReason.END);
        }
        if (request.task().equals("QA")) {
            String text = json.writeValueAsString(answer(prompt));
            return new Response(text, "local-llm-1", in, text.length() / 4, 0, StopReason.END);
        }
        List<Line> lines = new ArrayList<>();
        Matcher m = LINE.matcher(prompt);
        while (m.find()) {
            lines.add(new Line(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), m.group(3).strip()));
        }
        Object answer = switch (request.task()) {
            case "SUMMARY_CHAPTERS" -> summary(lines);
            case "QUIZ_DRAFT" -> quiz(lines);
            case "FLASHCARDS" -> flashcards(lines);
            default -> throw new IllegalArgumentException(request.task());
        };
        String text = json.writeValueAsString(answer);
        return new Response(text, "local-llm-1", in, text.length() / 4, 0, StopReason.END);
    }

    /** Answers with the first excerpt, citing it; LLM_BADCITE cites an excerpt that wasn't given. */
    private static Map<String, Object> answer(String prompt) {
        Matcher m = EXCERPT.matcher(prompt);
        if (!m.find()) {
            return Map.of("answer", "The excerpts don't cover this.", "supported", false, "citations", List.of());
        }
        String id = prompt.contains("LLM_BADCITE") ? "E99" : m.group(1);
        return Map.of("answer", "According to the lecture: " + m.group(4).strip(), "supported", true,
            "citations", List.of(Map.of("excerpt", id, "startMs", Long.parseLong(m.group(2)))));
    }

    private static Map<String, Object> summary(List<Line> lines) {
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) {
            chapters.add(Map.of("startMs", lines.get(i).startMs(), "title", title(lines.get(i).text())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shortSummary", lines.isEmpty() ? "No speech." : lines.getFirst().text());
        out.put("detailedSummary", String.join(" ", lines.stream().map(Line::text).toList()));
        out.put("chapters", chapters);
        return out;
    }

    private static Map<String, Object> quiz(List<Line> lines) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int i = 0; i < lines.size() && questions.size() < 5; i++) {
            Line line = lines.get(i);
            List<String> options = new ArrayList<>(List.of(line.text()));
            for (int j = 1; options.size() < 4 && j < lines.size(); j++) {
                String other = lines.get((i + j) % lines.size()).text();
                if (!options.contains(other)) {
                    options.add(other);
                }
            }
            while (options.size() < 2) {
                options.add("None of the above");
            }
            questions.add(Map.of("prompt", "Which statement did the lecturer make around " + line.startMs() / 1000 + " s?",
                "options", options, "answerIndex", 0, "explanation", "Stated directly in the lecture.",
                "sourceStartMs", line.startMs(), "sourceEndMs", line.endMs()));
        }
        return Map.of("questions", questions);
    }

    private static Map<String, Object> flashcards(List<Line> lines) {
        return Map.of("cards", lines.stream()
            .map(l -> Map.of("front", "What does the lecture say about " + title(l.text()).toLowerCase() + "?", "back", l.text(),
                "sourceStartMs", l.startMs()))
            .toList());
    }

    private static String title(String text) {
        String[] words = text.replaceAll("[.,]", "").split("\\s+");
        return String.join(" ", List.of(words).subList(0, Math.min(6, words.length)));
    }
}
