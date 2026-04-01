package ca.aksentiev.emailfilter.filter.spam;

import ca.aksentiev.emailfilter.filter.EmailFilter;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.FilterResult;

/**
 * Spam gate filter — always runs first in the chain.
 * Orchestrates the three-layer scoring pipeline:
 * PreProcessor → SpamAssassin → LLM, then combines weighted scores.
 * <p>
 * Whitelist check runs first — if matched, returns SKIPPED.
 * If raw SA score exceeds threshold, LLM is skipped.
 */
public class SpamFilter implements EmailFilter {

    private static final String NAME = "spam-filter";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public FilterResult process(EmailMessage message) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
