package ca.aksentiev.emailfilter.lab.engine;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.springframework.stereotype.Component;

/**
 * Evaluates a {@link Condition} against an {@link EmailMessage}.
 * Supports bare shorthand, all-of, any-of, and not compositions.
 */
@Component
public class ConditionEvaluator {

    public boolean evaluate(Condition condition, EmailMessage email) {
        if (condition == null) {
            return false;
        }

        // Boolean compositions
        if (condition.allOf() != null && !condition.allOf().isEmpty()) {
            return condition.allOf().stream().allMatch(c -> evaluate(c, email));
        }
        if (condition.anyOf() != null && !condition.anyOf().isEmpty()) {
            return condition.anyOf().stream().anyMatch(c -> evaluate(c, email));
        }
        if (condition.not() != null) {
            return !evaluate(condition.not(), email);
        }

        // Bare shorthand — single condition field
        return evaluateBare(condition, email);
    }

    private boolean evaluateBare(Condition condition, EmailMessage email) {
        if (condition.subjectStartsWith() != null) {
            return email.subject().toLowerCase().startsWith(condition.subjectStartsWith().toLowerCase());
        }
        if (condition.subjectEndsWith() != null) {
            return email.subject().toLowerCase().endsWith(condition.subjectEndsWith().toLowerCase());
        }
        if (condition.subjectContains() != null) {
            return email.subject().toLowerCase().contains(condition.subjectContains().toLowerCase());
        }
        if (condition.fromDomain() != null) {
            return email.from().toLowerCase().contains(condition.fromDomain().toLowerCase());
        }
        if (condition.fromAddress() != null) {
            return email.from().equalsIgnoreCase(condition.fromAddress());
        }
        if (condition.fromAddressContains() != null) {
            return email.from().toLowerCase().contains(condition.fromAddressContains().toLowerCase());
        }
        return false;
    }

    /**
     * Returns a human-readable description of the condition for reporting.
     */
    public String describe(Condition condition) {
        if (condition.subjectStartsWith() != null) {
            return "subject-starts-with \"" + condition.subjectStartsWith() + "\"";
        }
        if (condition.subjectEndsWith() != null) {
            return "subject-ends-with \"" + condition.subjectEndsWith() + "\"";
        }
        if (condition.subjectContains() != null) {
            return "subject-contains \"" + condition.subjectContains() + "\"";
        }
        if (condition.fromDomain() != null) {
            return "from-domain \"" + condition.fromDomain() + "\"";
        }
        if (condition.fromAddress() != null) {
            return "from-address \"" + condition.fromAddress() + "\"";
        }
        if (condition.fromAddressContains() != null) {
            return "from-address-contains \"" + condition.fromAddressContains() + "\"";
        }
        if (condition.allOf() != null) {
            return "all-of [" + condition.allOf().size() + " conditions]";
        }
        if (condition.anyOf() != null) {
            return "any-of [" + condition.anyOf().size() + " conditions]";
        }
        if (condition.not() != null) {
            return "not(" + describe(condition.not()) + ")";
        }
        return "unknown";
    }
}
