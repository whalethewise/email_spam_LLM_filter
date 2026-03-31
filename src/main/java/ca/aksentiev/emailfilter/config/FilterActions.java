package ca.aksentiev.emailfilter.config;

/**
 * Actions to take when a filter matches or does not match.
 *
 * @param onMatch   action when conditions are met (e.g. "move", "tag", "flag")
 * @param onNoMatch action when conditions are not met (e.g. "none")
 * @param moveTo    target folder for move actions
 */
public record FilterActions(
        String onMatch,
        String onNoMatch,
        String moveTo
) {
}
