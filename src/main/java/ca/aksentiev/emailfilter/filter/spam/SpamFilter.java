package ca.aksentiev.emailfilter.filter.spam;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.EmailFilter;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.FilterResult;
import ca.aksentiev.emailfilter.llm.LlmResponse;
import ca.aksentiev.emailfilter.llm.LlmScoringService;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorService;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import ca.aksentiev.emailfilter.scoring.ScoringService;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinClient;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spam gate filter — always runs first in the chain.
 * Orchestrates the three-layer scoring pipeline sequentially:
 * <ol>
 *   <li>Whitelist check — if matched, returns SKIPPED</li>
 *   <li>PreProcessor (Layer 1) — character normalization, brand detection, URL analysis</li>
 *   <li>SpamAssassin (Layer 2) — header/Bayesian analysis via spamc</li>
 *   <li>LLM (Layer 3) — semantic analysis via Ollama (skipped if SA score exceeds threshold)</li>
 * </ol>
 * Uses {@link ScoringService} for weighted average with graceful degradation.
 */
@Component
public class SpamFilter implements EmailFilter {

    private static final Logger log = LoggerFactory.getLogger(SpamFilter.class);
    private static final String NAME = "spam-filter";

    private final SpamFilterProperties properties;
    private final PreProcessorService preProcessorService;
    private final SpamAssassinClient spamAssassinClient;
    private final LlmScoringService llmScoringService;
    private final ScoringService scoringService;
    private final Whitelist whitelist;

    public SpamFilter(
            SpamFilterProperties properties,
            PreProcessorService preProcessorService,
            SpamAssassinClient spamAssassinClient,
            LlmScoringService llmScoringService,
            ScoringService scoringService) {
        this.properties = properties;
        this.preProcessorService = preProcessorService;
        this.spamAssassinClient = spamAssassinClient;
        this.llmScoringService = llmScoringService;
        this.scoringService = scoringService;
        this.whitelist = new Whitelist(properties.getWhitelist());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public FilterResult process(EmailMessage message) {
        if (whitelist.isWhitelisted(message.from())) {
            log.info("Sender <{}> is whitelisted, skipping spam filter", message.from());
            return FilterResult.skipped("Sender whitelisted: " + message.from());
        }

        ParsedEmail parsedEmail = toParsedEmail(message);

        // Layer 1: Pre-processor
        PreProcessorFindings ppFindings = preProcessorService.analyze(parsedEmail);
        log.debug("Pre-processor score: {} for '{}'", ppFindings.score(), message.subject());

        // Layer 2: SpamAssassin
        String rawContent = buildRawContent(message);
        SpamAssassinResult saResult = spamAssassinClient.check(rawContent);
        log.debug("SpamAssassin score: {} (available: {}) for '{}'", saResult.rawScore(), saResult.available(), message.subject());

        // Layer 3: LLM (skip if SA score exceeds threshold)
        LlmResponse llmResponse = null;
        boolean llmSkipped = false;
        if (saResult.available() && saResult.rawScore() >= properties.getSkipLlmAboveScore()) {
            log.info("SA raw score {} exceeds threshold {}, skipping LLM for '{}'",
                    saResult.rawScore(), properties.getSkipLlmAboveScore(), message.subject());
            llmSkipped = true;
        } else {
            llmResponse = llmScoringService.score(parsedEmail, ppFindings);
            log.debug("LLM score: {} (available: {}) for '{}'",
                    llmResponse.score(), llmResponse.available(), message.subject());
        }

        // Combine scores
        ScoreResult scoreResult = scoringService.score(ppFindings, saResult, llmResponse);

        // Map category to action
        String action = resolveAction(scoreResult.category());

        log.info("Spam filter result for '{}': score={} category={} action={}",
                message.subject(), scoreResult.finalScore(), scoreResult.category(), action);

        Map<String, Object> metadata = buildMetadata(scoreResult, ppFindings, saResult, llmResponse, llmSkipped);
        return FilterResult.processed(scoreResult.finalScore(), action, scoreResult.llmReason(), metadata);
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    String resolveAction(ScoreCategory category) {
        SpamFilterProperties.Actions actions = properties.getActions();
        return switch (category) {
            case SAFE -> actions.safe();
            case REVIEW -> actions.review();
            case SPAM -> actions.spam();
        };
    }

    private ParsedEmail toParsedEmail(EmailMessage message) {
        return new ParsedEmail(
                message.messageId(),
                message.subject(),
                message.from(),
                message.fromName(),
                message.to(),
                message.bodyText(),
                message.headers(),
                Instant.now());
    }

    private String buildRawContent(EmailMessage message) {
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            raw.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        raw.append("\r\n");
        raw.append(message.bodyText());
        return raw.toString();
    }

    private Map<String, Object> buildMetadata(
            ScoreResult scoreResult,
            PreProcessorFindings ppFindings,
            SpamAssassinResult saResult,
            LlmResponse llmResponse,
            boolean llmSkipped) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("scoreResult", scoreResult);
        metadata.put("subjectTag", scoreResult.subjectTag());
        metadata.put("ppScore", ppFindings.score());
        metadata.put("saAvailable", saResult.available());
        metadata.put("saRawScore", saResult.rawScore());
        metadata.put("llmSkipped", llmSkipped);
        if (llmResponse != null) {
            metadata.put("llmAvailable", llmResponse.available());
            metadata.put("llmScore", llmResponse.score());
        }
        return Map.copyOf(metadata);
    }
}
