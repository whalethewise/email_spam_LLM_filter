package ca.aksentiev.emailfilter.spamassassin;

/**
 * Immutable result from SpamAssassin, carrying the spam score,
 * matched rules, and SPF/DKIM/DMARC results.
 */
public record SpamAssassinResult() {
}
