package ca.aksentiev.emailfilter.lab.report;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.lab.engine.FilterDefinition;
import ca.aksentiev.emailfilter.lab.engine.FilterResult;
import ca.aksentiev.emailfilter.lab.engine.FilterType;
import ca.aksentiev.emailfilter.lab.engine.ResolvedAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Formats and prints the lab session report.
 */
@Component
public class LabReportPrinter {

    private static final Logger log = LoggerFactory.getLogger(LabReportPrinter.class);
    private static final String LINE = "──────────────────────────────────────────────────────────────────────";
    private static final String DOUBLE_LINE = "══════════════════════════════════════════════════════════════════════";

    public String buildReport(String filterName, FilterDefinition filter, String folder,
                              List<EmailMessage> emails, List<FilterResult> results) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        int total = emails.size();

        // Per-email detail
        for (int i = 0; i < total; i++) {
            EmailMessage email = emails.get(i);
            FilterResult result = results.get(i);
            String index = String.format("[%02d/%02d]", i + 1, total);

            printEmailResult(out, index, filter.type(), email, result);
            out.println(LINE);
        }

        // Summary
        out.println(DOUBLE_LINE);
        out.printf("  SUMMARY — %s [%s] — %s%n", filterName, filter.type(), folder);
        out.printf("  Emails processed : %d%n", total);
        out.println(DOUBLE_LINE);
        out.println();

        if (filter.type() == FilterType.SCORING) {
            printScoreDistribution(out, results);
        }
        printActionDistribution(out, results);

        // Promote block
        out.println();
        out.println(DOUBLE_LINE);
        out.println("  PROMOTE THIS FILTER");
        out.println("  1. Copy the YAML block below into the main app's filters.yml");
        out.println("  2. Add filter name to the account's filter list");
        out.println("  3. Run: curl -X POST http://192.168.10.180:8081/api/reload/filters");
        out.println(DOUBLE_LINE);
        out.println();
        out.printf("  %s:%n", filterName);
        printPromoteYaml(out, filter);

        return sw.toString();
    }

    public void printToConsole(String report) {
        System.out.println(report);
    }

    public void writeToFile(String report, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path resolved = Path.of(filePath).toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            if (!resolved.startsWith(cwd)) {
                log.error("Report path resolves outside working directory: {}", resolved);
                return;
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter(resolved.toFile()))) {
                writer.print(report);
                log.info("Report written to: {}", resolved);
            }
        } catch (IOException e) {
            log.error("Failed to write report to '{}': {}", filePath, e.getMessage());
        }
    }

    private void printEmailResult(PrintWriter out, String index, FilterType type,
                                   EmailMessage email, FilterResult result) {
        String subject = truncate(email.subject(), 60);

        if (result.isLeave()) {
            String reason = result.reason() != null ? result.reason() : "";
            out.printf("%s  Result : LEAVE%s%n", index, reason.isEmpty() ? "" : " (" + reason + ")");
            out.printf("         From   : %s%n", email.from());
            out.printf("         Subject: %s%n", subject);
            return;
        }

        switch (type) {
            case SCORING -> {
                String category = result.score() >= 8 ? "SPAM      " :
                                  result.score() >= 4 ? "BORDERLINE" : "LEGITIMATE";
                out.printf("%s  Score:  %d  [%s]%n", index, result.score(), category);
                printActionLines(out, result);
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
                if (result.reason() != null) {
                    out.printf("         Reason : %s%n", result.reason());
                }
            }
            case EXTRACTION -> {
                boolean isNone = result.llmResponse() == null;
                out.printf("%s  Result : %s%n", index, isNone ? "NONE" : "RESPONSE");
                printActionLines(out, result);
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
                if (!isNone && result.llmResponse() != null) {
                    out.printf("         Extract: %s%n", truncate(result.llmResponse(), 70));
                }
            }
            case LOGISTICS -> {
                out.printf("%s  Rule   : %s%n", index, result.matchedRule() != null ? result.matchedRule() : "none");
                printActionLines(out, result);
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
            }
        }
    }

    private void printActionLines(PrintWriter out, FilterResult result) {
        List<ResolvedAction> actions = result.actions();
        if (actions.isEmpty()) {
            out.printf("         Action : LEAVE%n");
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            String label = i == 0 ? "Action " : "       ";
            out.printf("         %s: %s%n", label, formatAction(actions.get(i)));
        }
    }

    private String formatAction(ResolvedAction action) {
        return switch (action.type()) {
            case LEAVE -> "LEAVE";
            case FLAG -> "FLAG";
            case MOVE_TO_JUNK -> "MOVE-TO-JUNK";
            case MOVE_TO_FOLDER -> "MOVE-TO-FOLDER → " + action.targetFolder();
            case DELETE -> "DELETE";
            case SEND_EMAIL -> "SEND-EMAIL → " + action.emailTo();
        };
    }

    private void printScoreDistribution(PrintWriter out, List<FilterResult> results) {
        int low = 0, mid = 0, high = 0, errors = 0;
        for (FilterResult r : results) {
            if (r.isLeave() && "whitelisted".equals(r.reason())) {
                continue;
            }
            if (r.score() >= 1 && r.score() <= 3) low++;
            else if (r.score() >= 4 && r.score() <= 7) mid++;
            else if (r.score() >= 8) high++;
            else errors++;
        }
        out.printf("  SCORE DISTRIBUTION          ACTIONS%n");
        out.printf("  1-3  (legitimate) : %2d", low);
        printActionCountPadded(out, results, 0);
        out.printf("  4-7  (borderline) : %2d", mid);
        printActionCountPadded(out, results, 1);
        out.printf("  8-10 (spam)       : %2d", high);
        printActionCountPadded(out, results, 2);
        out.printf("  errors            : %2d%n", errors);
        out.println();
    }

    private void printActionCountPadded(PrintWriter out, List<FilterResult> results, int line) {
        Map<String, Integer> counts = countActions(results);
        String[] keys = {"leave", "move-to-junk", "move-to-folder", "send-email", "flag", "delete"};
        if (line < keys.length) {
            out.printf("      %-15s: %2d%n", keys[line], counts.getOrDefault(keys[line], 0));
        } else {
            out.println();
        }
    }

    private void printActionDistribution(PrintWriter out, List<FilterResult> results) {
        Map<String, Integer> counts = countActions(results);
        out.println("  ACTIONS");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.printf("  %-18s: %2d%n", entry.getKey(), entry.getValue());
        }
        out.println();
    }

    private Map<String, Integer> countActions(List<FilterResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("leave", 0);
        counts.put("move-to-junk", 0);
        counts.put("move-to-folder", 0);
        counts.put("send-email", 0);
        counts.put("flag", 0);
        counts.put("delete", 0);
        for (FilterResult r : results) {
            if (r.actions().isEmpty()) {
                counts.merge("leave", 1, Integer::sum);
                continue;
            }
            for (ResolvedAction action : r.actions()) {
                String key = switch (action.type()) {
                    case LEAVE -> "leave";
                    case MOVE_TO_JUNK -> "move-to-junk";
                    case MOVE_TO_FOLDER -> "move-to-folder";
                    case SEND_EMAIL -> "send-email";
                    case FLAG -> "flag";
                    case DELETE -> "delete";
                };
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private void printPromoteYaml(PrintWriter out, FilterDefinition filter) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        Map<String, Object> filterMap = filterToMap(filter);
        String yamlStr = yaml.dump(filterMap);
        for (String line : yamlStr.split("\n")) {
            out.printf("    %s%n", line);
        }
    }

    private Map<String, Object> filterToMap(FilterDefinition filter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", filter.type().name().toLowerCase());
        map.put("enabled", filter.enabled());
        if (filter.ollamaModel() != null) {
            map.put("ollama-model", filter.ollamaModel());
        }
        if (filter.whitelist() != null) {
            Map<String, Object> wl = new LinkedHashMap<>();
            wl.put("addresses", filter.whitelist().addresses());
            wl.put("domains", filter.whitelist().domains());
            wl.put("patterns", filter.whitelist().patterns());
            map.put("whitelist", wl);
        }
        if (filter.prompt() != null) {
            map.put("prompt", filter.prompt());
        }
        if (filter.scoring() != null) {
            Map<String, Object> scoring = new LinkedHashMap<>();
            if (filter.scoring().weights() != null) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("preprocessor", filter.scoring().weights().preprocessor());
                w.put("spamassassin", filter.scoring().weights().spamassassin());
                w.put("llm", filter.scoring().weights().llm());
                scoring.put("weights", w);
            }
            if (filter.scoring().thresholds() != null) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("safe-max", filter.scoring().thresholds().safeMax());
                t.put("review-max", filter.scoring().thresholds().reviewMax());
                scoring.put("thresholds", t);
            }
            if (filter.scoring().actions() != null) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("safe", filter.scoring().actions().safe());
                a.put("review", filter.scoring().actions().review());
                a.put("spam", filter.scoring().actions().spam());
                scoring.put("actions", a);
            }
            map.put("scoring", scoring);
        }
        if (filter.sourceDomains() != null) {
            map.put("source-domains", filter.sourceDomains());
        }
        if (filter.rules() != null) {
            map.put("rules", filter.rules());
        }
        return map;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
