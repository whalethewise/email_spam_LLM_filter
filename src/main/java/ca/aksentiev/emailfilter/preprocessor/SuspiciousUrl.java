package ca.aksentiev.emailfilter.preprocessor;

/**
 * A URL flagged as suspicious by the pre-processor.
 *
 * @param url    the flagged URL
 * @param reason why it was flagged (e.g. "IP address URL", "suspicious TLD")
 */
public record SuspiciousUrl(String url, String reason) {}
