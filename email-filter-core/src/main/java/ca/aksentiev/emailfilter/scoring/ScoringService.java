package ca.aksentiev.emailfilter.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.llm.LlmResponse;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.spamassassin.SpamAssassinResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Combines scores from all three layers (PreProcessor, SpamAssassin, LLM)
 * into a final weighted score with graceful degradation when layers are unavailable.
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final SpamFilterProperties properties;

    public ScoringService(SpamFilterProperties properties) {
        this.properties = properties;
    }

    /**
     * Computes the final weighted score from all three layer results.
     *
     * @param ppFindings pre-processor findings (Layer 1, always available)
     * @param saResult   SpamAssassin result (Layer 2)
     * @param llmResult  LLM result (Layer 3), may be null if skipped
     * @return combined score result with category, tag, and per-layer details
     */
    public ScoreResult score(PreProcessorFindings ppFindings, SpamAssassinResult saResult, LlmResponse llmResult) {
        SpamFilterProperties.Weights weights = properties.getWeights();

        boolean llmSkipped = llmResult == null;
        boolean llmAvailable = !llmSkipped && llmResult.available();

        LayerScore ppLayer = new LayerScore("preprocessor", ppFindings.score(), weights.preprocessor(), true);
        LayerScore saLayer =
                new LayerScore("spamassassin", saResult.normalizedScore(), weights.spamassassin(), saResult.available());
        LayerScore llmLayer = new LayerScore(
                "llm", llmSkipped ? 0.0 : llmResult.score(), weights.llm(), llmAvailable);

        List<LayerScore> layerScores = List.of(ppLayer, saLayer, llmLayer);

        double finalScore = computeWeightedAverage(layerScores);
        ScoreCategory category = categorize(finalScore);
        String subjectTag = formatSubjectTag(ppLayer, saLayer, llmLayer, finalScore);
        String llmReason = llmAvailable ? llmResult.reason() : "";

        return new ScoreResult(finalScore, category, layerScores, subjectTag, llmReason);
    }

    double computeWeightedAverage(List<LayerScore> layers) {
        List<LayerScore> available = new ArrayList<>();
        for (LayerScore layer : layers) {
            if (layer.available()) {
                available.add(layer);
            }
        }

        if (available.isEmpty()) {
            log.warn("No scoring layers available — defaulting to SAFE (score 1)");
            return 1.0;
        }

        double totalWeight = 0.0;
        for (LayerScore layer : available) {
            totalWeight += layer.weight();
        }

        double weightedSum = 0.0;
        for (LayerScore layer : available) {
            double redistributedWeight = layer.weight() / totalWeight;
            weightedSum += layer.score() * redistributedWeight;
        }

        return Math.max(1.0, Math.min(10.0, Math.round(weightedSum * 10.0) / 10.0));
    }

    ScoreCategory categorize(double score) {
        SpamFilterProperties.Thresholds thresholds = properties.getThresholds();
        if (score <= thresholds.safeMax()) {
            return ScoreCategory.SAFE;
        }
        if (score <= thresholds.reviewMax()) {
            return ScoreCategory.REVIEW;
        }
        return ScoreCategory.SPAM;
    }

    String formatSubjectTag(LayerScore pp, LayerScore sa, LayerScore llm, double finalScore) {
        String ppStr = formatLayerTag("PP", pp);
        String saStr = formatLayerTag("SA", sa);
        String llmStr = formatLayerTag("LLM", llm);
        return "[" + ppStr + "/" + saStr + "/" + llmStr + "=" + Math.round(finalScore) + "]";
    }

    private String formatLayerTag(String prefix, LayerScore layer) {
        if (!layer.available()) {
            return prefix + ":-";
        }
        return prefix + ":" + Math.round(layer.score());
    }
}
