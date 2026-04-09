package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

public record ExtractionActions(
        List<ActionDefinition> onResponse,
        List<ActionDefinition> always) {

    public ExtractionActions {
        onResponse = onResponse != null ? onResponse : List.of();
        always = always != null ? always : List.of();
    }
}
