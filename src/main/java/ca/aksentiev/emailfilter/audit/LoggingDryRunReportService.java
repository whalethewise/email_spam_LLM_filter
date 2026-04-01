package ca.aksentiev.emailfilter.audit;

import ca.aksentiev.emailfilter.action.DryRunReportService;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 0 dry-run report implementation — logs decisions to SLF4J.
 * Will be replaced with a full implementation that sends email reports
 * and saves to disk once the pipeline is validated.
 */
@Service
public class LoggingDryRunReportService implements DryRunReportService {

    private static final Logger log = LoggerFactory.getLogger(LoggingDryRunReportService.class);

    @Override
    public void recordDecision(ParsedEmail email, ScoreResult score, String wouldTakeAction, String reason) {
        log.info(
                "[DRY-RUN] Email '{}' from <{}> — would {}: {}",
                email.subject(),
                email.from(),
                wouldTakeAction,
                reason);
    }

    @Override
    public String generateReport() {
        log.info("[DRY-RUN] Report generation not yet implemented — see log entries above");
        return "Dry-run report: see application logs for individual decisions.";
    }

    @Override
    public void sendReport() {
        log.info("[DRY-RUN] Report delivery not yet implemented");
    }
}
