package ca.aksentiev.emailfilter.lab.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.springframework.stereotype.Component;

/**
 * Executes logistics-type filters: rule matching with no LLM call.
 * On first matching rule, all of that rule's actions are returned for execution.
 */
@Component
public class LogisticsFilter {

    private final ConditionEvaluator conditionEvaluator;
    private final VariableResolver variableResolver;

    public LogisticsFilter(ConditionEvaluator conditionEvaluator, VariableResolver variableResolver) {
        this.conditionEvaluator = conditionEvaluator;
        this.variableResolver = variableResolver;
    }

    public FilterResult execute(FilterDefinition filter, EmailMessage email) {
        if (filter.rules() == null || filter.rules().isEmpty()) {
            return FilterResult.leave();
        }

        for (LogisticsRule rule : filter.rules()) {
            if (conditionEvaluator.evaluate(rule.condition(), email)) {
                String ruleName = conditionEvaluator.describe(rule.condition());
                Map<String, String> vars = variableResolver.emailVariables(email);
                List<ResolvedAction> resolved = resolveAll(rule.actions(), vars);
                return new FilterResult(resolved, 0, null, null, ruleName);
            }
        }

        return FilterResult.leave();
    }

    private List<ResolvedAction> resolveAll(List<ActionDefinition> actions, Map<String, String> vars) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<ResolvedAction> out = new ArrayList<>(actions.size());
        for (ActionDefinition action : actions) {
            out.add(new ResolvedAction(
                    action.type(),
                    action.folder() != null ? variableResolver.resolve(action.folder(), vars) : null,
                    action.to() != null ? variableResolver.resolve(action.to(), vars) : null,
                    action.subject() != null ? variableResolver.resolve(action.subject(), vars) : null,
                    action.body() != null ? variableResolver.resolve(action.body(), vars) : null));
        }
        return out;
    }
}
