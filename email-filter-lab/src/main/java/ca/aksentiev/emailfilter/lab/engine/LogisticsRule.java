package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

public record LogisticsRule(
        Condition condition,
        List<ActionDefinition> actions) {}
