package ca.aksentiev.emailfilter.llm;

import java.util.List;
import java.util.stream.Collectors;

import java.time.Instant;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.preprocessor.BrandImpersonation;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.preprocessor.SuspiciousUrl;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

/**
 * Layer 3 of the spam scoring pipeline. Sends enriched email context
 * (including pre-processor findings) to Ollama via Spring AI's ChatClient
 * and returns a structured spam assessment.
 * <p>
 * Never throws exceptions to callers — returns {@link LlmResponse#unavailable()}
 * when Ollama cannot be reached.
 */
@Service
public class LlmScoringService {

    private static final Logger log = LoggerFactory.getLogger(LlmScoringService.class);

    private static final int MAX_BODY_LENGTH = 2000;

    static final String SYSTEM_PROMPT =
            """
            You are a spam detection system. Analyze the email content and pre-processor findings provided.
            Return ONLY valid JSON in this exact format, with no other text:
            {"score": <number 1-10>, "reason": "<brief explanation>"}

            Scoring guide:
            1-3: Legitimate email
            4-6: Suspicious, possibly spam
            7-10: Very likely spam or phishing

            Consider the pre-processor findings carefully — they highlight character substitution tricks, \
            brand impersonation, and suspicious URLs that humans would miss.""";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public LlmScoringService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void warmup() {
        log.info("******************* --> LLM warmup call starting");
        long start = System.currentTimeMillis();

        ParsedEmail fakeEmail = new ParsedEmail(
                "<warmup@test>", "LLM Warmup Test", "test@example.com", "Test Sender",
                List.of("me@example.com"), "This is a warmup email to test LLM response time.",
                java.util.Map.of(), Instant.now());
        PreProcessorFindings fakeFindings = new PreProcessorFindings(
                "LLM Warmup Test", "This is a warmup email to test LLM response time.",
                List.of(), List.of(), false, false, 1.0);

        LlmResponse result = score(fakeEmail, fakeFindings);

        long elapsed = System.currentTimeMillis() - start;
        log.info("================ LLM warmup complete in {}ms: score={} available={} reason='{}'",
                elapsed, result.score(), result.available(), result.reason());
    }

    /**
     * Scores an email using LLM semantic analysis enriched with pre-processor findings.
     *
     * @param email    the parsed email
     * @param findings pre-processor analysis results
     * @return LLM spam assessment, or unavailable result on error
     */
    public LlmResponse score(ParsedEmail email, PreProcessorFindings findings) {
        String userPrompt = buildUserPrompt(email, findings);

        OllamaChatOptions options = OllamaChatOptions.builder()
                .numPredict(256)
                .temperature(0.0)
                .disableThinking()
                .build();

        log.info("LLM request: prompt length={}, system length={}, options=numPredict=256,temp=0.3",
                userPrompt.length(), SYSTEM_PROMPT.length());

        try {
            String response = chatClient
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .options(options)
                    .call()
                    .content();

            log.info("================ LLM raw response (length={}): {}", response != null ? response.length() : 0, response);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Ollama unavailable — {}", e.getMessage());
            return LlmResponse.unavailable();
        }
    }

    String buildUserPrompt(ParsedEmail email, PreProcessorFindings findings) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("PRE-PROCESSOR FINDINGS:\n");
        appendBrandImpersonations(prompt, findings.brandImpersonations());
        appendSuspiciousUrls(prompt, findings.suspiciousUrls());

        if (findings.unicodeSpoofingDetected()) {
            prompt.append("- UNICODE SPOOFING: Cyrillic/Greek lookalike characters detected\n");
        }
        if (findings.zeroWidthCharsDetected()) {
            prompt.append("- ZERO-WIDTH CHARACTERS: Hidden zero-width characters detected\n");
        }
        prompt.append("- Pre-processor score: ").append((int) findings.score()).append("/10\n");

        prompt.append("\nEMAIL:\n");
        prompt.append("Subject: ").append(findings.normalizedSubject()).append("\n");
        prompt.append("From: ")
                .append(email.fromName())
                .append(" <")
                .append(email.from())
                .append(">\n");
        prompt.append("Body: ").append(truncateBody(findings.normalizedBody())).append("\n");

        return prompt.toString();
    }

    LlmResponse parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty LLM response");
            return new LlmResponse(5.0, "LLM response parsing failed", true);
        }

        try {
            String jsonStr = extractJson(response);
            JsonNode json = objectMapper.readTree(jsonStr);

            JsonNode scoreNode = json.get("score");
            JsonNode reasonNode = json.get("reason");

            if (scoreNode == null || reasonNode == null) {
                log.warn("LLM response JSON missing 'score' or 'reason' fields");
                log.debug("Raw LLM response:\n{}", response);
                return new LlmResponse(5.0, "LLM response parsing failed", true);
            }

            double score = Math.max(1.0, Math.min(10.0, scoreNode.asDouble()));
            return new LlmResponse(score, reasonNode.asText(), true);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response — {}", e.getMessage());
            log.debug("Raw LLM response:\n{}", response);
            return new LlmResponse(5.0, "LLM response parsing failed", true);
        }
    }

    private String extractJson(String response) {
        // Strip markdown code fences (```json ... ``` or ``` ... ```)
        String stripped = response.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("(?s)```", "").trim();

        // Find the first balanced {...} block
        int start = stripped.indexOf('{');
        if (start < 0) {
            return response;
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
        return stripped.substring(start);
    }

    private void appendBrandImpersonations(StringBuilder prompt, List<BrandImpersonation> impersonations) {
        for (BrandImpersonation imp : impersonations) {
            prompt.append("- BRAND IMPERSONATION: '")
                    .append(imp.originalText())
                    .append("' normalizes to '")
                    .append(imp.normalizedText())
                    .append("' (brand: ")
                    .append(imp.brandName())
                    .append("). Legitimate domains: ")
                    .append(imp.legitimateDomains().stream().collect(Collectors.joining(", ")))
                    .append("\n");
        }
    }

    private void appendSuspiciousUrls(StringBuilder prompt, List<SuspiciousUrl> urls) {
        for (SuspiciousUrl url : urls) {
            prompt.append("- SUSPICIOUS URL: ")
                    .append(url.url())
                    .append(" (")
                    .append(url.reason())
                    .append(")\n");
        }
    }

    private String truncateBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_BODY_LENGTH) + "... [truncated]";
    }
}
