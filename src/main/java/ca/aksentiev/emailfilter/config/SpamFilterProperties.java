package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.spam-filter} — configuration specific to the
 * spam gate filter, including scoring weights, thresholds, and per-tier actions.
 */
@ConfigurationProperties(prefix = "emailfilter.spam-filter")
public class SpamFilterProperties {

    private boolean enabled;
    private String ollamaModel;
    private double skipLlmAboveScore;
    private String brandsPath;
    private String charSubstitutionsPath;
    private Weights weights;
    private Thresholds thresholds;
    private Actions actions;

    public SpamFilterProperties(boolean enabled, String ollamaModel, double skipLlmAboveScore,
                                String brandsPath, String charSubstitutionsPath,
                                Weights weights, Thresholds thresholds, Actions actions) {
        this.enabled = enabled;
        this.ollamaModel = ollamaModel;
        this.skipLlmAboveScore = skipLlmAboveScore;
        this.brandsPath = brandsPath;
        this.charSubstitutionsPath = charSubstitutionsPath;
        this.weights = weights;
        this.thresholds = thresholds;
        this.actions = actions;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getOllamaModel() {
        return ollamaModel;
    }

    public double getSkipLlmAboveScore() {
        return skipLlmAboveScore;
    }

    public String getBrandsPath() {
        return brandsPath;
    }

    public String getCharSubstitutionsPath() {
        return charSubstitutionsPath;
    }

    public Weights getWeights() {
        return weights;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public Actions getActions() {
        return actions;
    }

    /**
     * Scoring layer weights. Should sum to 1.0.
     * When a layer is unavailable, remaining weights are redistributed.
     *
     * @param preprocessor  weight for Layer 1 (pre-processor)
     * @param spamassassin  weight for Layer 2 (SpamAssassin)
     * @param llm           weight for Layer 3 (LLM)
     */
    public record Weights(double preprocessor, double spamassassin, double llm) {
    }

    /**
     * Score thresholds that determine how emails are classified.
     * Scores 1 to safeMax are safe, safeMax+1 to reviewMax need review, above reviewMax is spam.
     *
     * @param safeMax   maximum score to consider safe (inclusive)
     * @param reviewMax maximum score to consider review-worthy (inclusive); above this is spam
     */
    public record Thresholds(int safeMax, int reviewMax) {
    }

    /**
     * IMAP actions per spam tier.
     *
     * @param safe   action for safe emails (score 1–safeMax), e.g. "none"
     * @param review action for review emails (score safeMax+1–reviewMax), e.g. "move-to-review"
     * @param spam   action for spam emails (score reviewMax+1–10), e.g. "move-to-junk"
     */
    public record Actions(String safe, String review, String spam) {
    }
}
