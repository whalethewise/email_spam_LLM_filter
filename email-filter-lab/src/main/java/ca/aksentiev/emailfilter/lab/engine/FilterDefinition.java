package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;
import java.util.Map;

public record FilterDefinition(
        FilterType type,
        boolean enabled,
        String ollamaModel,
        WhitelistConfig whitelist,
        PreProcessorConfig preProcessor,
        SpamAssassinConfig spamAssassin,
        ScoringConfig scoring,
        Map<String, String> sourceDomains,
        Map<String, String> sourceSubjects,
        Map<String, Object> data,
        String prompt,
        ExtractionActions actions,
        List<LogisticsRule> rules) {

    public FilterDefinition {
        if (type == null) {
            type = FilterType.SCORING;
        }
    }
}
