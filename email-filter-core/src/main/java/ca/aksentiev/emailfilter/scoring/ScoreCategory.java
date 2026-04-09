package ca.aksentiev.emailfilter.scoring;

/**
 * Classification category based on the final weighted spam score.
 * Thresholds are configurable via {@link ca.aksentiev.emailfilter.config.SpamFilterProperties.Thresholds}.
 */
public enum ScoreCategory {
    SAFE,
    REVIEW,
    SPAM
}
