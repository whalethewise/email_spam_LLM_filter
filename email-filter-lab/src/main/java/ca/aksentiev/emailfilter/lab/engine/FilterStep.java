package ca.aksentiev.emailfilter.lab.engine;

/**
 * One filter's outcome inside a chain run. Records which filter ran and what
 * it produced; {@code stoppedChain} is true when this step's destructive
 * action ended the chain — subsequent filters did not run.
 */
public record FilterStep(
        String filterName,
        FilterType filterType,
        FilterResult result,
        boolean stoppedChain) {}
