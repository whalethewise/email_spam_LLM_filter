package ca.aksentiev.emailfilter.filter;

import java.util.Map;

/**
 * Outcome of a single filter evaluation.
 *
 * @param status   whether the filter processed or skipped the email
 * @param score    filter's spam/confidence score (1-10), 0 if not applicable
 * @param action   the action to take (e.g. "leave", "move-to-review", "delete")
 * @param reason   human-readable explanation
 * @param metadata arbitrary key-value data for downstream consumers
 */
public record FilterResult(Status status, double score, String action, String reason, Map<String, Object> metadata) {

    /**
     * Whether the filter processed the email or skipped it.
     * SKIPPED means the filter did not apply (e.g. whitelisted) —
     * the chain continues to the next filter.
     * PROCESSED means the filter evaluated and produced an action.
     */
    public enum Status {
        PROCESSED,
        SKIPPED
    }

    /**
     * Creates a PROCESSED result with the given score, action, and reason.
     */
    public static FilterResult processed(double score, String action, String reason) {
        return new FilterResult(Status.PROCESSED, score, action, reason, Map.of());
    }

    /**
     * Creates a PROCESSED result with metadata.
     */
    public static FilterResult processed(double score, String action, String reason, Map<String, Object> metadata) {
        return new FilterResult(Status.PROCESSED, score, action, reason, metadata);
    }

    /**
     * Creates a SKIPPED result with a reason.
     */
    public static FilterResult skipped(String reason) {
        return new FilterResult(Status.SKIPPED, 0.0, "leave", reason, Map.of());
    }

    /**
     * Returns true if the chain should continue past this filter.
     * A "leave" action or SKIPPED status means continue.
     */
    public boolean shouldContinueChain() {
        return status == Status.SKIPPED || "leave".equals(action);
    }
}
