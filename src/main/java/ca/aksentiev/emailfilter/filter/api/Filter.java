package ca.aksentiev.emailfilter.filter.api;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;

/**
 * Core filter contract. Each filter evaluates a parsed email
 * and returns a {@link FilterResult} indicating what action to take.
 */
public interface Filter {

    FilterResult evaluate(ParsedEmail email);
}
