package ca.aksentiev.emailfilter.config;

import java.util.List;

/**
 * A group of conditions joined by a logical operator (AND/OR).
 * Supports two levels of nesting: a top-level group can contain
 * nested groups, but nested groups contain only rules — no deeper nesting.
 *
 * @param operator logical operator joining the items in this group
 * @param groups   nested condition groups (second level only)
 * @param rules    leaf-level condition rules
 */
public record ConditionGroup(
        Operator operator,
        List<ConditionGroup> groups,
        List<ConditionRule> rules
) {

    public enum Operator {
        AND, OR
    }
}
