package ca.aksentiev.emailfilter.lab.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.springframework.stereotype.Component;

/**
 * Runs a sequence of filters against a single email with stop-on-first-
 * destructive-action semantics (FILTER-SPEC.md §24-27).
 *
 * <p>Non-destructive actions ({@code FLAG}, {@code SEND_EMAIL}, {@code LEAVE})
 * accumulate but do not stop the chain. Destructive actions
 * ({@code MOVE_TO_FOLDER}, {@code MOVE_TO_JUNK}, {@code DELETE}) execute and
 * then end the chain — subsequent filters do not run.
 */
@Component
public class FilterChain {

    private final FilterDispatcher dispatcher;

    public FilterChain(FilterDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public ChainResult run(List<String> filterNames,
                            Map<String, FilterDefinition> filterDefinitions,
                            EmailMessage email) {
        List<FilterStep> steps = new ArrayList<>();
        List<ResolvedAction> aggregated = new ArrayList<>();

        for (String name : filterNames) {
            FilterDefinition definition = filterDefinitions.get(name);
            if (definition == null) {
                throw new IllegalArgumentException("Filter not found: " + name);
            }

            FilterResult result = dispatcher.dispatch(definition, email);
            aggregated.addAll(result.actions());

            boolean destructive = result.isDestructive();
            steps.add(new FilterStep(name, definition.type(), result, destructive));

            if (destructive) {
                break;
            }
        }

        return new ChainResult(steps, aggregated);
    }
}
