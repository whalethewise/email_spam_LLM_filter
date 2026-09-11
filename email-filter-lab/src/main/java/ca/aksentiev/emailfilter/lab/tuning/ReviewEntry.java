package ca.aksentiev.emailfilter.lab.tuning;

/**
 * One scored email in a "review" run — the clean subject plus any pre-existing
 * tag it carried. {@code whitelisted} is true when the sender matched
 * {@code lab.tuning.whitelist} and the LLM call was skipped.
 */
public record ReviewEntry(String from, String subject, String originalTag, TuningScoreResult result, boolean whitelisted) {}
