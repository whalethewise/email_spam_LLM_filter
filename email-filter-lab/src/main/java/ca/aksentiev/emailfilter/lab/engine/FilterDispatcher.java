package ca.aksentiev.emailfilter.lab.engine;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dispatches to the correct filter implementation based on filter type.
 */
@Component
public class FilterDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FilterDispatcher.class);

    private final ScoringFilter scoringFilter;
    private final ExtractionFilter extractionFilter;
    private final LogisticsFilter logisticsFilter;

    public FilterDispatcher(ScoringFilter scoringFilter,
                            ExtractionFilter extractionFilter,
                            LogisticsFilter logisticsFilter) {
        this.scoringFilter = scoringFilter;
        this.extractionFilter = extractionFilter;
        this.logisticsFilter = logisticsFilter;
    }

    public FilterResult dispatch(FilterDefinition filter, EmailMessage email) {
        if (!filter.enabled()) {
            log.debug("Filter disabled, returning LEAVE");
            return FilterResult.leave("disabled");
        }

        return switch (filter.type()) {
            case SCORING -> scoringFilter.execute(filter, email);
            case EXTRACTION -> extractionFilter.execute(filter, email);
            case LOGISTICS -> logisticsFilter.execute(filter, email);
        };
    }
}
