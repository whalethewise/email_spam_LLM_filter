package ca.aksentiev.emailfilter.filter;

/**
 * Core filter contract. Each filter in the chain implements this interface.
 * Filters are resolved by name from account YAML config and executed
 * in declared order by the {@code FilterChainDispatcher}.
 */
public interface EmailFilter {

    /**
     * Returns the unique name of this filter, matching the name
     * used in account and filter YAML configuration.
     */
    String getName();

    /**
     * Processes an email message and returns the filter's decision.
     *
     * @param message the email to evaluate
     * @return the filter result with status, score, action, and reason
     */
    FilterResult process(EmailMessage message);

    /**
     * Returns whether this filter is currently enabled.
     * Disabled filters are skipped by the dispatcher.
     */
    boolean isEnabled();
}
