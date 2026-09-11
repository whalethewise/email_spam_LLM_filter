package ca.aksentiev.emailfilter.lab.tuning;

import java.util.List;

import ca.aksentiev.emailfilter.lab.config.LabProperties;
import org.springframework.stereotype.Component;

/** Builds and prints the move-plan report for the lab's "reshuffle" mode. */
@Component
public class ReshuffleReportPrinter {

    private static final String SEP = "═".repeat(70);
    private static final String THIN = "─".repeat(70);

    private final LabProperties properties;

    public ReshuffleReportPrinter(LabProperties properties) {
        this.properties = properties;
    }

    public void print(List<ReshufflePlan> plan, boolean dryRun) {
        String report = buildReport(plan, dryRun);
        TuningReportWriter.print(report);
        TuningReportWriter.writeToFile(report, properties.getRun().reportFile(), "reshuffle");
    }

    private String buildReport(List<ReshufflePlan> plan, boolean dryRun) {
        LabProperties.Tuning.Reshuffle cfg = properties.getTuning().reshuffle();
        StringBuilder sb = new StringBuilder();

        sb.append("\n").append(SEP).append("\n");
        sb.append(String.format("  RESHUFFLE PLAN — %s (%d emails)%n",
                properties.getImap().folder(), plan.size()));
        sb.append(String.format("  Mode: %s%n", dryRun ? "DRY-RUN" : "LIVE"));
        sb.append(String.format("  Weights: PP=%.0f%% SA=%.0f%% LLM=%.0f%%%n",
                cfg.weightPreprocessor() * 100, cfg.weightSpamassassin() * 100, cfg.weightLlm() * 100));
        sb.append(String.format("  Thresholds: safe ≤ %d, review ≤ %d, spam > %d%n",
                cfg.safeMax(), cfg.reviewMax(), cfg.reviewMax()));
        sb.append(SEP).append("\n\n");

        int toInbox = 0, keepReview = 0, toJunk = 0, whitelisted = 0;

        for (int i = 0; i < plan.size(); i++) {
            ReshufflePlan p = plan.get(i);
            sb.append(String.format("[%02d/%02d]  PP:%d  SA:%d  LLM:%d  →  Combined:%d  [%s]%n",
                    i + 1, plan.size(), p.ppScore(), p.saScore(), p.llmScore(),
                    p.combinedScore(), p.action()));
            sb.append(String.format("         From   : %s%n", p.email().from()));
            sb.append(String.format("         Old    : \"%s\"%n", p.originalSubject()));
            sb.append(String.format("         New    : \"%s\"%n", p.newSubject()));
            sb.append(String.format("         Dest   : %s%n", p.destination()));
            sb.append(String.format("         Reason : %s%n", p.reason()));
            sb.append(THIN).append("\n");

            switch (p.action()) {
                case "MOVE_TO_INBOX" -> toInbox++;
                case "KEEP_IN_REVIEW" -> keepReview++;
                case "MOVE_TO_JUNK" -> toJunk++;
                case "WHITELISTED" -> whitelisted++;
                default -> { }
            }
        }

        sb.append("\n").append(SEP).append("\n");
        sb.append("  PLAN SUMMARY\n");
        sb.append(String.format("  Move to INBOX        : %d%n", toInbox));
        sb.append(String.format("  Whitelisted → INBOX  : %d%n", whitelisted));
        sb.append(String.format("  Keep in review       : %d%n", keepReview));
        sb.append(String.format("  Move to Junk         : %d%n", toJunk));
        sb.append(SEP).append("\n");

        if (dryRun) {
            sb.append("[DRY-RUN] No emails were moved. Set lab.run.dry-run=false to execute.\n");
        }

        return sb.toString();
    }
}
