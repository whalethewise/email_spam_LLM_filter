package ca.aksentiev.emailfilter.lab.tuning;

import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.lab.engine.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Scores a single email against an arbitrary prompt template (supporting
 * {@code {email.from}}, {@code {email.subject}}, {@code {email.body}}) via
 * the default Ollama model. Used by the lab's "review" and "reshuffle" modes
 * to isolate LLM prompt quality — no pre-processor or SpamAssassin involved.
 */
@Component
public class TuningLlmScorer {

    private static final Logger log = LoggerFactory.getLogger(TuningLlmScorer.class);

    private final ChatClient chatClient;
    private final VariableResolver variableResolver;

    public TuningLlmScorer(ChatClient.Builder chatClientBuilder, VariableResolver variableResolver) {
        this.chatClient = chatClientBuilder.build();
        this.variableResolver = variableResolver;
    }

    public TuningScoreResult score(EmailMessage email, String promptTemplate) {
        Map<String, String> vars = variableResolver.emailVariables(email);
        String prompt = variableResolver.resolve(promptTemplate, vars);

        OllamaChatOptions options = OllamaChatOptions.builder()
                .temperature(0.0)
                .disableThinking()
                .build();

        try {
            String raw = chatClient
                    .prompt(prompt)
                    .options(options)
                    .call()
                    .content();
            return parse(email, raw);
        } catch (Exception e) {
            log.error("LLM call failed for '{}': {}", email.subject(), e.getMessage());
            return new TuningScoreResult(7, "LLM call failed — treating as suspicious: " + e.getMessage());
        }
    }

    private TuningScoreResult parse(EmailMessage email, String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "").trim();

        String json = extractJson(cleaned);
        if (json == null) {
            log.warn("Could not find balanced JSON in LLM response for '{}': {}",
                    email.subject(), truncate(cleaned, 300));
            return new TuningScoreResult(7, "Parse failure — treating as suspicious");
        }

        int score = extractInt(json, "score");
        String reason = extractString(json, "reason");

        if (score == 0) {
            log.warn("Score missing or zero for '{}', defaulting to 7 (suspicious)", email.subject());
            return new TuningScoreResult(7, "Score missing from LLM response — treating as suspicious. Raw reason: " + reason);
        }

        return new TuningScoreResult(Math.max(1, Math.min(10, score)), reason);
    }

    private static final int MAX_JSON_EXTRACT_LENGTH = 10_000;

    /**
     * Strips markdown code fences and returns the first balanced {@code {...}}
     * block, or {@code null} if none closes properly. A depth-tracked scan
     * (rather than first-'{'/last-'}') avoids grabbing a broken span when the
     * response contains braces outside the intended JSON — e.g. reasoning
     * text, or content quoted from the email itself.
     */
    private String extractJson(String response) {
        if (response.isEmpty()) {
            return null;
        }
        String bounded = response.length() > MAX_JSON_EXTRACT_LENGTH
                ? response.substring(0, MAX_JSON_EXTRACT_LENGTH) : response;
        String stripped = bounded.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("(?s)```", "").trim();

        int start = stripped.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private int extractInt(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx == -1) {
            return 0;
        }
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx == -1) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractString(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx == -1) {
            return "";
        }
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx == -1) {
            return "";
        }
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && json.charAt(i - 1) != '\\') {
                break;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
