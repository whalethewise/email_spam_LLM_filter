package ca.aksentiev.emailfilter.filter.api;

/**
 * Outcome of a single filter evaluation, carrying the action to take,
 * whether to stop the chain, and a human-readable reason.
 */
public record FilterResult(FilterAction action, boolean stop, String reason) {
}
