package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

/**
 * Outcome of a single filter evaluation. Carries the list of resolved actions
 * to execute (in declaration order) plus filter-specific metadata.
 *
 * <p>An empty action list means LEAVE — the filter chose not to act and the
 * email is passed through.
 */
public record FilterResult(
        List<ResolvedAction> actions,
        int score,
        String reason,
        String llmResponse,
        String matchedRule) {

    public FilterResult {
        actions = actions != null ? List.copyOf(actions) : List.of();
    }

    public static FilterResult leave() {
        return new FilterResult(List.of(), 0, null, null, null);
    }

    public static FilterResult leave(String reason) {
        return new FilterResult(List.of(), 0, reason, null, null);
    }

    /**
     * True if no action will execute against the mailbox — used by the chain
     * dispatcher to decide whether to continue.
     */
    public boolean isLeave() {
        return actions.isEmpty()
                || actions.stream().allMatch(a -> a.type() == ActionType.LEAVE);
    }

    /**
     * Returns true if any action in the result is destructive (move/delete).
     * Non-destructive actions (FLAG, SEND_EMAIL, LEAVE) do not stop the chain.
     */
    public boolean isDestructive() {
        for (ResolvedAction action : actions) {
            switch (action.type()) {
                case MOVE_TO_JUNK, MOVE_TO_FOLDER, DELETE -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    /**
     * Returns the destructive action if present, otherwise the first action,
     * otherwise LEAVE. Used for short-form reporting.
     */
    public ActionType primaryActionType() {
        if (actions.isEmpty()) {
            return ActionType.LEAVE;
        }
        for (ResolvedAction action : actions) {
            switch (action.type()) {
                case MOVE_TO_JUNK, MOVE_TO_FOLDER, DELETE -> {
                    return action.type();
                }
                default -> { }
            }
        }
        return actions.get(0).type();
    }
}
