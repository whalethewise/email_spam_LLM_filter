package ca.aksentiev.emailfilter.action;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.scoring.ScoreResult;

/**
 * Accumulates dry-run decisions and generates digest reports.
 * When dry-run mode is enabled, EmailActionService delegates to this
 * service instead of executing real email actions.
 */
public interface DryRunReportService {

    /**
     * Records a decision that would have been taken on an email.
     *
     * @param email           the parsed email
     * @param score           the scoring result
     * @param wouldTakeAction the action that would have been executed (e.g. "move-to-review")
     * @param reason          explanation for the decision
     */
    void recordDecision(ParsedEmail email, ScoreResult score, String wouldTakeAction, String reason);

    /**
     * Generates a human-readable report of all accumulated decisions.
     *
     * @return the formatted report text
     */
    String generateReport();

    /**
     * Generates and sends/saves the report according to configured delivery settings.
     */
    void sendReport();
}
