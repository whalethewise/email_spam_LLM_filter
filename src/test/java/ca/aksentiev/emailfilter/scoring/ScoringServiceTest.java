package ca.aksentiev.emailfilter.scoring;

import java.util.List;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.llm.LlmResponse;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoringServiceTest {

    private ScoringService service;

    @BeforeEach
    void setUp() {
        SpamFilterProperties properties = new SpamFilterProperties(
                true,
                "mistral",
                8.0,
                "classpath:brands.json",
                "classpath:char_substitutions.json",
                new SpamFilterProperties.Weights(0.20, 0.35, 0.45),
                new SpamFilterProperties.Thresholds(3, 6),
                new SpamFilterProperties.Actions("none", "move-to-review", "move-to-junk"));
        service = new ScoringService(properties);
    }

    @Test
    void allThreeLayersAvailable() {
        PreProcessorFindings pp = ppFindings(3.0);
        SpamAssassinResult sa = saResult(8.0, true);
        LlmResponse llm = new LlmResponse(9.0, "Phishing detected", true);

        ScoreResult result = service.score(pp, sa, llm);

        // weighted: 3*0.20 + 8*0.35 + 9*0.45 = 0.6 + 2.8 + 4.05 = 7.45 → 7.5
        assertThat(result.finalScore()).isCloseTo(7.5, within(0.1));
        assertThat(result.category()).isEqualTo(ScoreCategory.SPAM);
        assertThat(result.layerScores()).hasSize(3);
        assertThat(result.llmReason()).isEqualTo("Phishing detected");
    }

    @Test
    void llmUnavailableRedistributesWeights() {
        PreProcessorFindings pp = ppFindings(4.0);
        SpamAssassinResult sa = saResult(6.0, true);
        LlmResponse llm = LlmResponse.unavailable();

        ScoreResult result = service.score(pp, sa, llm);

        // PP weight: 0.20/(0.20+0.35) = 0.3636, SA weight: 0.35/(0.20+0.35) = 0.6364
        // weighted: 4*0.3636 + 6*0.6364 = 1.4545 + 3.8182 = 5.2727 → 5.3
        assertThat(result.finalScore()).isCloseTo(5.3, within(0.1));
        assertThat(result.category()).isEqualTo(ScoreCategory.REVIEW);
        assertThat(result.llmReason()).isEmpty();
    }

    @Test
    void saUnavailableRedistributesWeights() {
        PreProcessorFindings pp = ppFindings(2.0);
        SpamAssassinResult sa = SpamAssassinResult.unavailable();
        LlmResponse llm = new LlmResponse(3.0, "Looks clean", true);

        ScoreResult result = service.score(pp, sa, llm);

        // PP weight: 0.20/(0.20+0.45) = 0.3077, LLM weight: 0.45/(0.20+0.45) = 0.6923
        // weighted: 2*0.3077 + 3*0.6923 = 0.6154 + 2.0769 = 2.6923 → 2.7
        assertThat(result.finalScore()).isCloseTo(2.7, within(0.1));
        assertThat(result.category()).isEqualTo(ScoreCategory.SAFE);
    }

    @Test
    void onlyPreprocessorAvailable() {
        PreProcessorFindings pp = ppFindings(5.0);
        SpamAssassinResult sa = SpamAssassinResult.unavailable();
        LlmResponse llm = LlmResponse.unavailable();

        ScoreResult result = service.score(pp, sa, llm);

        // Only PP available — its score is the final score
        assertThat(result.finalScore()).isEqualTo(5.0);
        assertThat(result.category()).isEqualTo(ScoreCategory.REVIEW);
    }

    @Test
    void llmSkippedWhenNull() {
        PreProcessorFindings pp = ppFindings(3.0);
        SpamAssassinResult sa = saResult(9.0, true);

        ScoreResult result = service.score(pp, sa, null);

        // LLM skipped (null), redistribute to PP and SA
        // PP: 0.20/(0.20+0.35) = 0.3636, SA: 0.6364
        // weighted: 3*0.3636 + 9*0.6364 = 1.0909 + 5.7273 = 6.8182 → 6.8
        assertThat(result.finalScore()).isCloseTo(6.8, within(0.1));
        assertThat(result.layerScores().get(2).available()).isFalse();
        assertThat(result.llmReason()).isEmpty();
    }

    @Test
    void categoryBoundaryValues() {
        // score 3 → SAFE (safeMax = 3)
        assertThat(service.categorize(3.0)).isEqualTo(ScoreCategory.SAFE);
        // score 3.1 → REVIEW
        assertThat(service.categorize(3.1)).isEqualTo(ScoreCategory.REVIEW);
        // score 4 → REVIEW
        assertThat(service.categorize(4.0)).isEqualTo(ScoreCategory.REVIEW);
        // score 6 → REVIEW (reviewMax = 6)
        assertThat(service.categorize(6.0)).isEqualTo(ScoreCategory.REVIEW);
        // score 6.1 → SPAM
        assertThat(service.categorize(6.1)).isEqualTo(ScoreCategory.SPAM);
        // score 7 → SPAM
        assertThat(service.categorize(7.0)).isEqualTo(ScoreCategory.SPAM);
    }

    @Test
    void subjectTagFormatWithAllLayers() {
        PreProcessorFindings pp = ppFindings(3.0);
        SpamAssassinResult sa = saResult(8.0, true);
        LlmResponse llm = new LlmResponse(9.0, "Spam", true);

        ScoreResult result = service.score(pp, sa, llm);

        assertThat(result.subjectTag()).matches("\\[PP:3/SA:8/LLM:9=\\d+]");
    }

    @Test
    void subjectTagWithUnavailableLayers() {
        PreProcessorFindings pp = ppFindings(3.0);
        SpamAssassinResult sa = SpamAssassinResult.unavailable();
        LlmResponse llm = new LlmResponse(9.0, "Spam", true);

        ScoreResult result = service.score(pp, sa, llm);

        assertThat(result.subjectTag()).contains("SA:-");
        assertThat(result.subjectTag()).contains("PP:3");
        assertThat(result.subjectTag()).contains("LLM:9");
    }

    @Test
    void subjectTagWithLlmUnavailable() {
        PreProcessorFindings pp = ppFindings(5.0);
        SpamAssassinResult sa = saResult(7.0, true);
        LlmResponse llm = LlmResponse.unavailable();

        ScoreResult result = service.score(pp, sa, llm);

        assertThat(result.subjectTag()).contains("LLM:-");
    }

    private PreProcessorFindings ppFindings(double score) {
        return new PreProcessorFindings("Subject", "Body", List.of(), List.of(), false, false, score);
    }

    private SpamAssassinResult saResult(double normalizedScore, boolean available) {
        return new SpamAssassinResult(normalizedScore * 2, normalizedScore, false, List.of("RULE_1"), available);
    }
}
