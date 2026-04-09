package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.springframework.stereotype.Component;

/**
 * Executes logistics-type filters: rule matching with no LLM call.
 */
@Component
public class LogisticsFilter {

    private final ConditionEvaluator conditionEvaluator;

    public LogisticsFilter(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    public FilterResult execute(FilterDefinition filter, EmailMessage email) {
        if (filter.rules() == null || filter.rules().isEmpty()) {
            return FilterResult.leave();
        }

        for (LogisticsRule rule : filter.rules()) {
            if (conditionEvaluator.evaluate(rule.condition(), email)) {
                String ruleName = conditionEvaluator.describe(rule.condition());
                return buildFromActions(rule.actions(), ruleName);
            }
        }

        return FilterResult.leave();
    }

    private FilterResult buildFromActions(List<ActionDefinition> actions, String ruleName) {
        if (actions == null || actions.isEmpty()) {
            return FilterResult.leave();
        }

        // Find first destructive action
        ActionDefinition primary = null;
        for (ActionDefinition action : actions) {
            if (action.type() != ActionType.LEAVE && action.type() != ActionType.FLAG) {
                primary = action;
                break;
            }
        }
        if (primary == null) {
            primary = actions.get(0);
        }

        return new FilterResult(
                primary.type(),
                primary.folder(),
                primary.to(),
                primary.subject(),
                primary.body(),
                0, null, null,
                ruleName);
    }
}
