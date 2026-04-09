package ca.aksentiev.emailfilter.audit;

import ca.aksentiev.emailfilter.action.AuditService;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 1 audit implementation — logs filter decisions to application logs.
 * Will be replaced with SQLite persistence in a later phase.
 */
@Service
public class LoggingAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditService.class);

    @Override
    public void record(ParsedEmail email, ScoreResult score, String action, boolean dryRun) {
        log.info(
                "{}AUDIT: email='{}' from=<{}> score={} category={} action={} reason='{}'",
                dryRun ? "[DRY-RUN] " : "",
                email.subject(),
                email.from(),
                score.finalScore(),
                score.category(),
                action,
                score.llmReason());
    }
}
