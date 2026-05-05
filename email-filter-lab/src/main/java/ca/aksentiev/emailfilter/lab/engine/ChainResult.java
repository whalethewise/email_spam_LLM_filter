package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

/**
 * Outcome of a chain run for a single email. Carries one {@link FilterStep}
 * per filter that actually executed plus the flattened list of resolved
 * actions to perform against the mailbox, in execution order.
 */
public record ChainResult(
        List<FilterStep> steps,
        List<ResolvedAction> actions) {

    public ChainResult {
        steps = steps != null ? List.copyOf(steps) : List.of();
        actions = actions != null ? List.copyOf(actions) : List.of();
    }

    public boolean isLeave() {
        return actions.isEmpty()
                || actions.stream().allMatch(a -> a.type() == ActionType.LEAVE);
    }

    /** Name of the filter whose destructive action stopped the chain, or null. */
    public String stoppedAtFilter() {
        for (FilterStep step : steps) {
            if (step.stoppedChain()) {
                return step.filterName();
            }
        }
        return null;
    }

    /** True if this chain ran more than one filter. */
    public boolean isMultiStep() {
        return steps.size() > 1;
    }
}
