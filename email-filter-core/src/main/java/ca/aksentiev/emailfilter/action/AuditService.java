package ca.aksentiev.emailfilter.action;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.scoring.ScoreResult;

/**
 * Audit logging contract. Records filter decisions and actions
 * taken on emails for review and debugging.
 */
public interface AuditService {

    /**
     * Records a filter decision and the action taken on an email.
     *
     * @param email  the parsed email
     * @param score  the scoring result
     * @param action the action that was taken (e.g. "move-to-review", "leave")
     * @param dryRun whether this was a dry-run (action not actually executed)
     */
    void record(ParsedEmail email, ScoreResult score, String action, boolean dryRun);
}
