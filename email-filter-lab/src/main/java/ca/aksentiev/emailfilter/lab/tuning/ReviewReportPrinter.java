package ca.aksentiev.emailfilter.lab.tuning;

import java.time.Instant;
import java.util.List;

import ca.aksentiev.emailfilter.lab.config.LabProperties;
import org.springframework.stereotype.Component;

/** Builds and prints the score-distribution report for the lab's "review" mode. */
@Component
public class ReviewReportPrinter {

    private static final String SEP = "═".repeat(70);
    private static final String THIN = "─".repeat(70);

    private final LabProperties properties;

    public ReviewReportPrinter(LabProperties properties) {
        this.properties = properties;
    }

    public void print(List<ReviewEntry> entries, String promptFile) {
        String report = buildReport(entries, promptFile);
        TuningReportWriter.print(report);
        TuningReportWriter.writeToFile(report, properties.getRun().reportFile(), "review");
    }

    private String buildReport(List<ReviewEntry> entries, String promptFile) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n").append(SEP).append("\n");
        sb.append("  PROMPT REVIEW REPORT\n");
        sb.append(String.format("  Run    : %s%n", Instant.now()));
        sb.append(String.format("  Folder : %s%n", properties.getImap().folder()));
        sb.append(String.format("  Model  : %s @ %s%n",
                properties.getOllama().model(), properties.getOllama().baseUrl()));
        sb.append(String.format("  Prompt : %s%n", promptFile));
        sb.append(String.format("  Emails : %d%n", entries.size()));
        sb.append(SEP).append("\n\n");

        int legitimate = 0, borderline = 0, spam = 0, whitelisted = 0;

        for (int i = 0; i < entries.size(); i++) {
            ReviewEntry e = entries.get(i);
            if (e.whitelisted()) {
                sb.append(String.format("[%02d/%02d]  WHITELISTED (LLM call skipped)%n", i + 1, entries.size()));
                sb.append(String.format("         From    : %s%n", e.from()));
                sb.append(String.format("         Subject : %s%n", e.subject()));
                sb.append(THIN).append("\n");
                whitelisted++;
                continue;
            }

            sb.append(String.format("[%02d/%02d]  Score: %2d  [%s]%n",
                    i + 1, entries.size(), e.result().score(), e.result().scoreLabel()));
            if (e.originalTag() != null && !e.originalTag().isEmpty()) {
                sb.append(String.format("         Original: %s%n", e.originalTag()));
            }
            sb.append(String.format("         From    : %s%n", e.from()));
            sb.append(String.format("         Subject : %s%n", e.subject()));
            sb.append(String.format("         Reason  : %s%n", e.result().reason()));
            sb.append(THIN).append("\n");

            int score = e.result().score();
            if (score <= 3) legitimate++;
            else if (score <= 7) borderline++;
            else spam++;
        }

        sb.append("\n").append(SEP).append("\n");
        sb.append("  SCORE DISTRIBUTION\n");
        sb.append(String.format("  1-3  (legitimate) : %d%n", legitimate));
        sb.append(String.format("  4-7  (borderline) : %d%n", borderline));
        sb.append(String.format("  8-10 (spam)       : %d%n", spam));
        sb.append(String.format("  whitelisted        : %d%n", whitelisted));
        sb.append(SEP).append("\n");

        return sb.toString();
    }
}
