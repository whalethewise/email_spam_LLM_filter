package ca.aksentiev.emailfilter.config;

/**
 * A single condition rule within a {@link ConditionGroup}.
 *
 * @param type   condition type — e.g. sender-contains, subject-contains, llm-prompt
 * @param value  match value for simple conditions
 * @param not    if true, negates the condition result
 * @param prompt LLM prompt text (used when type is llm-prompt)
 * @param expect expected LLM response (used when type is llm-prompt)
 */
public record ConditionRule(
        String type,
        String value,
        boolean not,
        String prompt,
        String expect
) {
}
