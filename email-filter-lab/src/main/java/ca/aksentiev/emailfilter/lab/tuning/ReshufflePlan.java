package ca.aksentiev.emailfilter.lab.tuning;

import ca.aksentiev.emailfilter.filter.EmailMessage;

/**
 * One planned move for a reshuffled email. {@code email} carries the raw
 * Jakarta Mail message so the executor can act on it without re-fetching.
 */
public record ReshufflePlan(
        EmailMessage email,
        String originalSubject,
        int ppScore,
        int saScore,
        int llmScore,
        int combinedScore,
        String newSubject,
        String destination,
        String action,
        String reason) {}
