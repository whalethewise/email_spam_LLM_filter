package ca.aksentiev.emailfilter.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.spam-filter} — configuration specific to the
 * spam gate filter, including scoring weights, thresholds, per-tier actions,
 * and whitelist.
 */
@ConfigurationProperties(prefix = "emailfilter.spam-filter")
public class SpamFilterProperties {

    private boolean enabled;
    private String ollamaModel;
    private double skipLlmAboveScore;
    private String brandsPath;
    private String charSubstitutionsPath;
    private String whitelistPath;
    private String llmPromptPath;
    private Weights weights;
    private Thresholds thresholds;
    private Actions actions;
    private WhitelistConfig whitelist;
    private Llm llm;

    public SpamFilterProperties(boolean enabled, String ollamaModel, double skipLlmAboveScore,
                                String brandsPath, String charSubstitutionsPath, String whitelistPath,
                                String llmPromptPath,
                                Weights weights, Thresholds thresholds, Actions actions,
                                WhitelistConfig whitelist, Llm llm) {
        this.enabled = enabled;
        this.ollamaModel = ollamaModel;
        this.skipLlmAboveScore = skipLlmAboveScore;
        this.brandsPath = brandsPath;
        this.charSubstitutionsPath = charSubstitutionsPath;
        this.whitelistPath = whitelistPath;
        this.llmPromptPath = llmPromptPath;
        this.weights = weights;
        this.thresholds = thresholds;
        this.actions = actions;
        this.whitelist = whitelist != null ? whitelist : new WhitelistConfig(List.of(), List.of(), List.of());
        this.llm = llm != null ? llm : new Llm(2000, 256, 0.0);
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

    public String getWhitelistPath() {
        return whitelistPath;
    }

    public String getLlmPromptPath() {
        return llmPromptPath;
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

    public WhitelistConfig getWhitelist() {
        return whitelist;
    }

    public Llm getLlm() {
        return llm;
    }

    public record Weights(double preprocessor, double spamassassin, double llm) {}

    public record Thresholds(int safeMax, int reviewMax) {}

    public record Actions(String safe, String review, String spam) {}

    /**
     * Whitelist configuration for the spam filter.
     *
     * @param addresses exact email addresses (e.g. "ceo@company.com")
     * @param domains   exact domains (e.g. "company.com")
     * @param patterns  wildcard patterns (e.g. "*@*.gov.ca")
     */
    public record WhitelistConfig(List<String> addresses, List<String> domains, List<String> patterns) {}

    public record Llm(int maxBodyLength, int numPredict, double temperature) {}
}
