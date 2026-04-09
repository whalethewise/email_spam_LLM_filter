package ca.aksentiev.emailfilter.lab.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorService;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinClient;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Executes scoring-type filters: pre-processor, SpamAssassin, LLM pipeline.
 */
@Component
public class ScoringFilter {

    private static final Logger log = LoggerFactory.getLogger(ScoringFilter.class);
    private static final int MAX_BODY_LENGTH = 8000;

    private final WhitelistMatcher whitelistMatcher;
    private final VariableResolver variableResolver;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PreProcessorService preProcessorService;
    private final SpamAssassinClient spamAssassinClient;

    public ScoringFilter(WhitelistMatcher whitelistMatcher,
                         VariableResolver variableResolver,
                         ChatClient.Builder chatClientBuilder,
                         ObjectMapper objectMapper,
                         PreProcessorService preProcessorService,
                         SpamAssassinClient spamAssassinClient) {
        this.whitelistMatcher = whitelistMatcher;
        this.variableResolver = variableResolver;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.preProcessorService = preProcessorService;
        this.spamAssassinClient = spamAssassinClient;
    }

    public FilterResult execute(FilterDefinition filter, EmailMessage email) {
        // 1. Whitelist check
        if (whitelistMatcher.matches(email.from(), filter.whitelist())) {
            return FilterResult.leave("whitelisted");
        }

        // 2. Pre-processor
        double ppScore = 1.0;
        String ppFindings = "";
        boolean ppRan = false;
        if (filter.preProcessor() != null && filter.preProcessor().enabled()) {
            try {
                ParsedEmail parsed = toParsedEmail(email);
                PreProcessorFindings findings = preProcessorService.analyze(parsed);
                ppScore = findings.score();
                ppFindings = formatFindings(findings);
                ppRan = true;
            } catch (Exception e) {
                log.warn("Pre-processor failed: {}", e.getMessage());
            }
        }

        // 3. SpamAssassin
        double saScore = 0.0;
        double saRawScore = 0.0;
        boolean saRan = false;
        boolean skipLlm = false;
        if (filter.spamAssassin() != null && filter.spamAssassin().enabled()) {
            try {
                SpamAssassinResult saResult = spamAssassinClient.check(email.bodyText());
                if (saResult.available()) {
                    saScore = saResult.normalizedScore();
                    saRawScore = saResult.rawScore();
                    saRan = true;
                    if (saRawScore >= filter.spamAssassin().skipLlmAboveScore()) {
                        skipLlm = true;
                    }
                }
            } catch (Exception e) {
                log.warn("SpamAssassin failed: {}", e.getMessage());
            }
        }

        // 4. LLM
        double llmScore = 0.0;
        String llmReason = "";
        boolean llmRan = false;
        if (!skipLlm && filter.prompt() != null) {
            try {
                Map<String, String> vars = new HashMap<>();
                vars.putAll(variableResolver.emailVariables(email));
                vars.put("preprocessor.findings", ppFindings);
                vars.put("preprocessor.score", String.valueOf((int) ppScore));
                vars.put("spamassassin.score", String.valueOf((int) saScore));
                vars.put("spamassassin.raw-score", String.valueOf(saRawScore));

                // Truncate body in variables
                String body = email.bodyText();
                if (body != null && body.length() > MAX_BODY_LENGTH) {
                    vars.put("email.body", body.substring(0, MAX_BODY_LENGTH) + "... [truncated]");
                }

                String resolvedPrompt = variableResolver.resolve(filter.prompt(), vars);

                OllamaChatOptions options = OllamaChatOptions.builder()
                        .model(filter.ollamaModel())
                        .temperature(0.0)
                        .disableThinking()
                        .build();

                String response = chatClient
                        .prompt(resolvedPrompt)
                        .options(options)
                        .call()
                        .content();

                response = stripThinkBlocks(response);
                LlmScoreResult parsed = parseLlmResponse(response);
                llmScore = parsed.score;
                llmReason = parsed.reason;
                llmRan = true;
            } catch (Exception e) {
                log.warn("LLM scoring failed: {}", e.getMessage());
                llmScore = 7.0;
                llmReason = "Parse failure — treating as suspicious";
                llmRan = true;
            }
        }

        // 5. Weighted average
        ScoringWeights weights = filter.scoring() != null && filter.scoring().weights() != null
                ? filter.scoring().weights()
                : new ScoringWeights(0.2, 0.35, 0.45);

        double totalWeight = 0;
        double weightedSum = 0;
        if (ppRan) {
            totalWeight += weights.preprocessor();
            weightedSum += ppScore * weights.preprocessor();
        }
        if (saRan) {
            totalWeight += weights.spamassassin();
            weightedSum += saScore * weights.spamassassin();
        }
        if (llmRan) {
            totalWeight += weights.llm();
            weightedSum += llmScore * weights.llm();
        }
        int finalScore = totalWeight > 0 ? (int) Math.round(weightedSum / totalWeight) : 1;
        finalScore = Math.max(1, Math.min(10, finalScore));

        // 6. Map to action
        ScoringThresholds thresholds = filter.scoring() != null && filter.scoring().thresholds() != null
                ? filter.scoring().thresholds()
                : new ScoringThresholds(3, 6);
        ScoringActions actions = filter.scoring() != null && filter.scoring().actions() != null
                ? filter.scoring().actions()
                : new ScoringActions("leave", "move-to-review", "move-to-junk");

        String actionName;
        if (finalScore <= thresholds.safeMax()) {
            actionName = actions.safe();
        } else if (finalScore <= thresholds.reviewMax()) {
            actionName = actions.review();
        } else {
            actionName = actions.spam();
        }

        ActionType actionType = mapActionName(actionName);

        // 7. Build subject tag
        StringBuilder tag = new StringBuilder("[");
        List<String> parts = new ArrayList<>();
        if (ppRan) {
            parts.add("PP:" + (int) ppScore);
        }
        if (saRan) {
            parts.add("SA:" + (int) saScore);
        }
        if (llmRan) {
            parts.add("LLM:" + (int) llmScore);
        }
        tag.append(String.join("/", parts));
        tag.append("=").append(finalScore).append("]");

        String reason = llmReason.isEmpty() ? tag.toString() : llmReason;

        return new FilterResult(
                actionType,
                actionType == ActionType.MOVE_TO_FOLDER ? "LLM-Spam-Review" : null,
                null, null, null,
                finalScore, reason, null, null);
    }

    private ActionType mapActionName(String name) {
        if (name == null) {
            return ActionType.LEAVE;
        }
        return switch (name.toLowerCase()) {
            case "leave", "none" -> ActionType.LEAVE;
            case "move-to-junk" -> ActionType.MOVE_TO_JUNK;
            case "move-to-review", "move-to-folder" -> ActionType.MOVE_TO_FOLDER;
            case "delete" -> ActionType.DELETE;
            case "flag" -> ActionType.FLAG;
            default -> ActionType.LEAVE;
        };
    }

    private LlmScoreResult parseLlmResponse(String response) {
        if (response == null || response.isBlank()) {
            return new LlmScoreResult(7, "Parse failure — treating as suspicious");
        }
        try {
            String json = extractJson(response);
            JsonNode node = objectMapper.readTree(json);
            JsonNode scoreNode = node.get("score");
            JsonNode reasonNode = node.get("reason");
            if (scoreNode == null || reasonNode == null) {
                return new LlmScoreResult(7, "Parse failure — treating as suspicious");
            }
            int score = Math.max(1, Math.min(10, scoreNode.asInt()));
            return new LlmScoreResult(score, reasonNode.asText());
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return new LlmScoreResult(7, "Parse failure — treating as suspicious");
        }
    }

    private String extractJson(String response) {
        String stripped = response.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("(?s)```", "").trim();
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

    private String stripThinkBlocks(String response) {
        if (response == null) {
            return "";
        }
        return response.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private String formatFindings(PreProcessorFindings findings) {
        StringBuilder sb = new StringBuilder();
        if (!findings.brandImpersonations().isEmpty()) {
            sb.append("Brand impersonations: ").append(findings.brandImpersonations().size()).append(". ");
        }
        if (!findings.suspiciousUrls().isEmpty()) {
            sb.append("Suspicious URLs: ").append(findings.suspiciousUrls().size()).append(". ");
        }
        if (findings.unicodeSpoofingDetected()) {
            sb.append("Unicode spoofing detected. ");
        }
        if (findings.zeroWidthCharsDetected()) {
            sb.append("Zero-width characters detected. ");
        }
        sb.append("Score: ").append((int) findings.score()).append("/10");
        return sb.toString();
    }

    private ParsedEmail toParsedEmail(EmailMessage email) {
        return new ParsedEmail(
                email.messageId(), email.subject(), email.from(), email.fromName(),
                email.to(), email.bodyText(), email.headers(), Instant.now());
    }

    private record LlmScoreResult(int score, String reason) {}
}
