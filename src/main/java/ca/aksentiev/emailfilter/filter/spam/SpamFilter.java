package ca.aksentiev.emailfilter.filter.spam;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.api.Filter;
import ca.aksentiev.emailfilter.filter.api.FilterResult;

/**
 * Spam gate filter — always runs first in the chain.
 * Orchestrates the three-layer scoring pipeline:
 * PreProcessor → SpamAssassin → LLM, then combines weighted scores.
 */
public class SpamFilter implements Filter {

    @Override
    public FilterResult evaluate(ParsedEmail email) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
