package ca.aksentiev.emailfilter.spamassassin;

import java.util.List;

/**
 * Immutable result from SpamAssassin (Layer 2).
 *
 * @param rawScore        SpamAssassin's raw score (0-20+)
 * @param normalizedScore raw score scaled to 1-10
 * @param isSpam          SpamAssassin's own spam/ham verdict
 * @param rules           triggered rule names
 * @param available       false if SpamAssassin was unreachable
 */
public record SpamAssassinResult(
        double rawScore, double normalizedScore, boolean isSpam, List<String> rules, boolean available) {

    /** Returns an unavailable result with zeroed scores. */
    public static SpamAssassinResult unavailable() {
        return new SpamAssassinResult(0.0, 0.0, false, List.of(), false);
    }
}
