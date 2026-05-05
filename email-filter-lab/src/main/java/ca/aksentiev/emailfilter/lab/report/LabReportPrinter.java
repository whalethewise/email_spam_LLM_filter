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
import ca.aksentiev.emailfilter.lab.engine.ChainResult;
import ca.aksentiev.emailfilter.lab.engine.FilterDefinition;
import ca.aksentiev.emailfilter.lab.engine.FilterResult;
import ca.aksentiev.emailfilter.lab.engine.FilterStep;
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

    public String buildReport(List<String> chain,
                               Map<String, FilterDefinition> definitions,
                               String folder,
                               List<EmailMessage> emails,
                               List<ChainResult> results) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        int total = emails.size();
        boolean isChain = chain.size() > 1;

        // Per-email detail
        for (int i = 0; i < total; i++) {
            EmailMessage email = emails.get(i);
            ChainResult result = results.get(i);
            String index = String.format("[%02d/%02d]", i + 1, total);

            if (isChain) {
                printChainResult(out, index, email, result);
            } else if (!result.steps().isEmpty()) {
                FilterStep onlyStep = result.steps().get(0);
                printSingleFilterResult(out, index, onlyStep.filterType(), email, result, onlyStep.result());
            } else {
                printSingleFilterResult(out, index, FilterType.SCORING, email, result, null);
            }
            out.println(LINE);
        }

        // Summary
        out.println(DOUBLE_LINE);
        if (isChain) {
            out.printf("  SUMMARY — chain: %s — %s%n", String.join(" → ", chain), folder);
        } else {
            String filterName = chain.get(0);
            FilterType type = definitions.get(filterName).type();
            out.printf("  SUMMARY — %s [%s] — %s%n", filterName, type, folder);
        }
        out.printf("  Emails processed : %d%n", total);
        out.println(DOUBLE_LINE);
        out.println();

        if (!isChain && containsScoring(chain, definitions)) {
            printScoreDistribution(out, results);
        }
        printActionDistribution(out, results);
        if (isChain) {
            printChainStopDistribution(out, results);
        }

        // Promote block
        out.println();
        out.println(DOUBLE_LINE);
        out.println("  PROMOTE TO PRODUCTION");
        out.println("  1. Copy the YAML block(s) below into the main app's filters.yml");
        out.println("  2. Add filter name(s) to the account's filter list, in this order");
        out.println("  3. Run: curl -X POST http://192.168.10.180:8081/api/reload");
        out.println(DOUBLE_LINE);
        out.println();
        for (String name : chain) {
            FilterDefinition def = definitions.get(name);
            if (def == null) {
                continue;
            }
            out.printf("  %s:%n", name);
            printPromoteYaml(out, def);
            out.println();
        }

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

    private void printChainResult(PrintWriter out, String index, EmailMessage email, ChainResult result) {
        String subject = truncate(email.subject(), 60);

        out.printf("%s  Chain run:%n", index);
        if (result.steps().isEmpty()) {
            out.printf("         (no filters ran)%n");
        }
        for (int s = 0; s < result.steps().size(); s++) {
            FilterStep step = result.steps().get(s);
            String stopMarker = step.stoppedChain() ? "  [STOPPED CHAIN]" : "";
            out.printf("         %02d. %-22s → %s%s%n",
                    s + 1, step.filterName(), formatStepActions(step.result()), stopMarker);
        }
        out.printf("         From   : %s%n", email.from());
        out.printf("         Subject: %s%n", subject);
        if (!result.actions().isEmpty()) {
            out.printf("         Final  : %s%n", formatAggregatedActions(result));
        }
    }

    private void printSingleFilterResult(PrintWriter out, String index, FilterType type,
                                          EmailMessage email, ChainResult result, FilterResult step) {
        String subject = truncate(email.subject(), 60);

        if (result.isLeave()) {
            String reason = step != null && step.reason() != null ? step.reason() : "";
            out.printf("%s  Result : LEAVE%s%n", index, reason.isEmpty() ? "" : " (" + reason + ")");
            out.printf("         From   : %s%n", email.from());
            out.printf("         Subject: %s%n", subject);
            return;
        }

        switch (type) {
            case SCORING -> {
                int score = step != null ? step.score() : 0;
                String category = score >= 8 ? "SPAM      " :
                                  score >= 4 ? "BORDERLINE" : "LEGITIMATE";
                out.printf("%s  Score:  %d  [%s]%n", index, score, category);
                printActionLines(out, result.actions());
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
                if (step != null && step.reason() != null) {
                    out.printf("         Reason : %s%n", step.reason());
                }
            }
            case EXTRACTION -> {
                boolean isNone = step == null || step.llmResponse() == null;
                out.printf("%s  Result : %s%n", index, isNone ? "NONE" : "RESPONSE");
                printActionLines(out, result.actions());
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
                if (!isNone) {
                    out.printf("         Extract: %s%n", truncate(step.llmResponse(), 70));
                }
            }
            case LOGISTICS -> {
                String rule = step != null && step.matchedRule() != null ? step.matchedRule() : "none";
                out.printf("%s  Rule   : %s%n", index, rule);
                printActionLines(out, result.actions());
                out.printf("         From   : %s%n", email.from());
                out.printf("         Subject: %s%n", subject);
            }
        }
    }

    private void printActionLines(PrintWriter out, List<ResolvedAction> actions) {
        if (actions.isEmpty()) {
            out.printf("         Action : LEAVE%n");
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            String label = i == 0 ? "Action " : "       ";
            out.printf("         %s: %s%n", label, formatAction(actions.get(i)));
        }
    }

    private String formatStepActions(FilterResult result) {
        List<ResolvedAction> actions = result.actions();
        if (actions.isEmpty()) {
            return "LEAVE";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            sb.append(formatAction(actions.get(i)));
        }
        return sb.toString();
    }

    private String formatAggregatedActions(ChainResult result) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.actions().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatAction(result.actions().get(i)));
        }
        return sb.toString();
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

    private boolean containsScoring(List<String> chain, Map<String, FilterDefinition> defs) {
        for (String name : chain) {
            FilterDefinition def = defs.get(name);
            if (def != null && def.type() == FilterType.SCORING) {
                return true;
            }
        }
        return false;
    }

    private void printScoreDistribution(PrintWriter out, List<ChainResult> results) {
        int low = 0, mid = 0, high = 0, errors = 0;
        for (ChainResult r : results) {
            FilterStep first = r.steps().isEmpty() ? null : r.steps().get(0);
            if (first == null) {
                continue;
            }
            int score = first.result().score();
            if (r.isLeave() && first.result().reason() != null
                    && "whitelisted".equals(first.result().reason())) {
                continue;
            }
            if (score >= 1 && score <= 3) low++;
            else if (score >= 4 && score <= 7) mid++;
            else if (score >= 8) high++;
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

    private void printActionCountPadded(PrintWriter out, List<ChainResult> results, int line) {
        Map<String, Integer> counts = countActions(results);
        String[] keys = {"leave", "move-to-junk", "move-to-folder", "send-email", "flag", "delete"};
        if (line < keys.length) {
            out.printf("      %-15s: %2d%n", keys[line], counts.getOrDefault(keys[line], 0));
        } else {
            out.println();
        }
    }

    private void printActionDistribution(PrintWriter out, List<ChainResult> results) {
        Map<String, Integer> counts = countActions(results);
        out.println("  ACTIONS");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.printf("  %-18s: %2d%n", entry.getKey(), entry.getValue());
        }
        out.println();
    }

    private void printChainStopDistribution(PrintWriter out, List<ChainResult> results) {
        Map<String, Integer> stops = new LinkedHashMap<>();
        int passedThrough = 0;
        for (ChainResult r : results) {
            String stoppedAt = r.stoppedAtFilter();
            if (stoppedAt == null) {
                passedThrough++;
            } else {
                stops.merge(stoppedAt, 1, Integer::sum);
            }
        }
        out.println("  CHAIN OUTCOMES");
        for (Map.Entry<String, Integer> entry : stops.entrySet()) {
            out.printf("  stopped at %-12s: %2d%n", entry.getKey(), entry.getValue());
        }
        out.printf("  passed through    : %2d%n", passedThrough);
        out.println();
    }

    private Map<String, Integer> countActions(List<ChainResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("leave", 0);
        counts.put("move-to-junk", 0);
        counts.put("move-to-folder", 0);
        counts.put("send-email", 0);
        counts.put("flag", 0);
        counts.put("delete", 0);
        for (ChainResult r : results) {
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
        if (filter.sourceSubjects() != null) {
            map.put("source-subjects", filter.sourceSubjects());
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
