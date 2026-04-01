package ca.aksentiev.emailfilter.filter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import ca.aksentiev.emailfilter.config.AccountProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves filter names from account YAML config to {@link EmailFilter} beans
 * and executes them in YAML-declared order.
 * <p>
 * Chain behavior: first non-"leave" action wins and stops the chain.
 * "leave" actions and SKIPPED results continue to the next filter.
 * If every filter passes, the email stays in inbox untouched.
 */
@Service
public class FilterChainDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FilterChainDispatcher.class);

    private final Map<String, EmailFilter> filtersByName;

    public FilterChainDispatcher(List<EmailFilter> filters) {
        this.filtersByName = filters.stream().collect(Collectors.toMap(EmailFilter::getName, Function.identity()));
        log.info("Registered {} filters: {}", filtersByName.size(), filtersByName.keySet());
    }

    /**
     * Dispatches an email through the filter chain defined by the account config.
     * Returns the first non-"leave" result, or a "leave" result if all filters pass.
     *
     * @param message the email to process
     * @param account the account whose filter list to use
     * @return the decisive filter result
     */
    public FilterResult dispatch(EmailMessage message, AccountProperties.Account account) {
        List<String> filterNames = account.getFilters();

        for (String filterName : filterNames) {
            EmailFilter filter = filtersByName.get(filterName);
            if (filter == null) {
                log.warn("Unknown filter '{}' in account '{}', skipping", filterName, account.getName());
                continue;
            }

            if (!filter.isEnabled()) {
                log.debug("Filter '{}' is disabled, skipping", filterName);
                continue;
            }

            log.debug("Running filter '{}' on email '{}'", filterName, message.subject());
            FilterResult result = filter.process(message);
            log.debug("Filter '{}' returned: status={} action={} reason='{}'",
                    filterName, result.status(), result.action(), result.reason());

            if (!result.shouldContinueChain()) {
                log.info("Filter '{}' stopped chain for '{}': action={} reason='{}'",
                        filterName, message.subject(), result.action(), result.reason());
                return result;
            }
        }

        log.debug("All filters passed for '{}', leaving in inbox", message.subject());
        return FilterResult.processed(0.0, "leave", "All filters passed");
    }
}
