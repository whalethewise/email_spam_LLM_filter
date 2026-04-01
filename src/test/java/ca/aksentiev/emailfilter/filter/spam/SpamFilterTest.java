package ca.aksentiev.emailfilter.filter.spam;

import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.FilterResult;
import ca.aksentiev.emailfilter.llm.LlmResponse;
import ca.aksentiev.emailfilter.llm.LlmScoringService;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorService;
import ca.aksentiev.emailfilter.scoring.LayerScore;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import ca.aksentiev.emailfilter.scoring.ScoringService;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinClient;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpamFilterTest {

    @Mock
    private PreProcessorService preProcessorService;

    @Mock
    private SpamAssassinClient spamAssassinClient;

    @Mock
    private LlmScoringService llmScoringService;

    @Mock
    private ScoringService scoringService;

    private SpamFilterProperties properties;
    private SpamFilter spamFilter;

    @BeforeEach
    void setUp() {
        properties = buildProperties(List.of(), List.of("trusted.com"), List.of());
        spamFilter = new SpamFilter(properties, preProcessorService, spamAssassinClient, llmScoringService, scoringService);
    }

    @Test
    void allThreeLayersAvailable() {
        PreProcessorFindings ppFindings = cleanFindings(3.0);
        SpamAssassinResult saResult = new SpamAssassinResult(5.0, 2.5, false, List.of("SPF_PASS"), true);
        LlmResponse llmResponse = new LlmResponse(2.0, "Legitimate email", true);
        ScoreResult scoreResult = new ScoreResult(2.5, ScoreCategory.SAFE, List.of(), "[PP:3/SA:3/LLM:2=3]", "Legitimate email");

        when(preProcessorService.analyze(any(ParsedEmail.class))).thenReturn(ppFindings);
        when(spamAssassinClient.check(any())).thenReturn(saResult);
        when(llmScoringService.score(any(ParsedEmail.class), any())).thenReturn(llmResponse);
        when(scoringService.score(ppFindings, saResult, llmResponse)).thenReturn(scoreResult);

        FilterResult result = spamFilter.process(testMessage("friend@gmail.com", "Hello"));

        assertThat(result.status()).isEqualTo(FilterResult.Status.PROCESSED);
        assertThat(result.score()).isEqualTo(2.5);
        assertThat(result.action()).isEqualTo("none");
        assertThat(result.shouldContinueChain()).isFalse();

        verify(preProcessorService).analyze(any());
        verify(spamAssassinClient).check(any());
        verify(llmScoringService).score(any(), any());
    }

    @Test
    void spamAssassinDownRedistributesWeights() {
        PreProcessorFindings ppFindings = cleanFindings(4.0);
        SpamAssassinResult saResult = SpamAssassinResult.unavailable();
        LlmResponse llmResponse = new LlmResponse(6.0, "Suspicious content", true);
        ScoreResult scoreResult = new ScoreResult(5.4, ScoreCategory.REVIEW, List.of(), "[PP:4/SA:-/LLM:6=5]", "Suspicious content");

        when(preProcessorService.analyze(any(ParsedEmail.class))).thenReturn(ppFindings);
        when(spamAssassinClient.check(any())).thenReturn(saResult);
        when(llmScoringService.score(any(ParsedEmail.class), any())).thenReturn(llmResponse);
        when(scoringService.score(ppFindings, saResult, llmResponse)).thenReturn(scoreResult);

        FilterResult result = spamFilter.process(testMessage("unknown@example.com", "Offer"));

        assertThat(result.status()).isEqualTo(FilterResult.Status.PROCESSED);
        assertThat(result.score()).isEqualTo(5.4);
        assertThat(result.action()).isEqualTo("move-to-review");
        assertThat(result.metadata()).containsEntry("saAvailable", false);

        verify(llmScoringService).score(any(), any());
    }

    @Test
    void llmSkippedWhenSaScoreExceedsThreshold() {
        PreProcessorFindings ppFindings = cleanFindings(7.0);
        // rawScore 15 exceeds skipLlmAboveScore of 8.0
        SpamAssassinResult saResult = new SpamAssassinResult(15.0, 7.5, true, List.of("BAYES_99"), true);
        ScoreResult scoreResult = new ScoreResult(7.3, ScoreCategory.SPAM, List.of(), "[PP:7/SA:8/LLM:-=7]", "");

        when(preProcessorService.analyze(any(ParsedEmail.class))).thenReturn(ppFindings);
        when(spamAssassinClient.check(any())).thenReturn(saResult);
        when(scoringService.score(any(), any(), isNull())).thenReturn(scoreResult);

        FilterResult result = spamFilter.process(testMessage("spam@evil.com", "Buy now!!!"));

        assertThat(result.status()).isEqualTo(FilterResult.Status.PROCESSED);
        assertThat(result.action()).isEqualTo("move-to-junk");
        assertThat(result.metadata()).containsEntry("llmSkipped", true);

        verify(llmScoringService, never()).score(any(), any());
    }

    @Test
    void whitelistedSenderSkipsFilter() {
        FilterResult result = spamFilter.process(testMessage("anyone@trusted.com", "Phishing attempt"));

        assertThat(result.status()).isEqualTo(FilterResult.Status.SKIPPED);
        assertThat(result.shouldContinueChain()).isTrue();
        assertThat(result.reason()).contains("whitelisted");

        verify(preProcessorService, never()).analyze(any());
        verify(spamAssassinClient, never()).check(any());
        verify(llmScoringService, never()).score(any(), any());
    }

    @Test
    void filterNameIsSpamFilter() {
        assertThat(spamFilter.getName()).isEqualTo("spam-filter");
    }

    @Test
    void enabledReflectsConfig() {
        assertThat(spamFilter.isEnabled()).isTrue();
    }

    private EmailMessage testMessage(String from, String subject) {
        return new EmailMessage(
                "<test@example.com>",
                from,
                "Test Sender",
                List.of("me@example.com"),
                subject,
                "Test body text",
                "",
                null,
                Map.of("Subject", subject, "From", from));
    }

    private PreProcessorFindings cleanFindings(double score) {
        return new PreProcessorFindings("Subject", "Body", List.of(), List.of(), false, false, score);
    }

    private SpamFilterProperties buildProperties(List<String> addresses, List<String> domains, List<String> patterns) {
        return new SpamFilterProperties(
                true,
                "mistral",
                8.0,
                "classpath:brands.json",
                "classpath:char_substitutions.json",
                new SpamFilterProperties.Weights(0.20, 0.35, 0.45),
                new SpamFilterProperties.Thresholds(3, 6),
                new SpamFilterProperties.Actions("none", "move-to-review", "move-to-junk"),
                new SpamFilterProperties.WhitelistConfig(addresses, domains, patterns));
    }
}
