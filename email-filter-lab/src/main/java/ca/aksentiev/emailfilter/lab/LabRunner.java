package ca.aksentiev.emailfilter.lab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.spam.Whitelist;
import ca.aksentiev.emailfilter.lab.config.LabProperties;
import ca.aksentiev.emailfilter.lab.engine.ActionExecutor;
import ca.aksentiev.emailfilter.lab.engine.ActionType;
import ca.aksentiev.emailfilter.lab.engine.ChainResult;
import ca.aksentiev.emailfilter.lab.engine.FilterChain;
import ca.aksentiev.emailfilter.lab.engine.FilterDefinition;
import ca.aksentiev.emailfilter.lab.engine.ResolvedAction;
import ca.aksentiev.emailfilter.lab.report.LabReportPrinter;
import ca.aksentiev.emailfilter.lab.tuning.ReshuffleExecutor;
import ca.aksentiev.emailfilter.lab.tuning.ReshufflePlan;
import ca.aksentiev.emailfilter.lab.tuning.ReshuffleReportPrinter;
import ca.aksentiev.emailfilter.lab.tuning.ReshuffleService;
import ca.aksentiev.emailfilter.lab.tuning.ReviewEntry;
import ca.aksentiev.emailfilter.lab.tuning.ReviewReportPrinter;
import ca.aksentiev.emailfilter.lab.tuning.SubjectTagParser;
import ca.aksentiev.emailfilter.lab.tuning.TuningLlmScorer;
import ca.aksentiev.emailfilter.lab.tuning.TuningScoreResult;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Main orchestrator for a lab session. Three modes, selected by {@code lab.run.mode}:
 *
 * <ul>
 *   <li>{@code filter} (default) — reads an IMAP folder, runs a
 *       staging-filters.yml filter chain, produces a report.
 *       {@code LAB_FILTER} accepts a single filter name or a comma-separated
 *       sequence (e.g. {@code logistics-filter,spam-filter,job-search-filter}) —
 *       the sequence executes with stop-on-first-destructive-action semantics
 *       (FILTER-SPEC.md §24-27).</li>
 *   <li>{@code review} — scores every email in the folder against a prompt
 *       file (LLM only, no PP/SA), prints a score-distribution report. Never
 *       mutates the mailbox.</li>
 *   <li>{@code reshuffle} — re-scores an already-tagged review folder against
 *       a prompt file, recombines with the existing PP/SA layers, and prints
 *       a move plan; executes it when {@code lab.run.dry-run=false}.</li>
 * </ul>
 */
@Component
public class LabRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LabRunner.class);

    private final LabProperties labProperties;
    private final Map<String, FilterDefinition> filterDefinitions;
    private final FilterChain filterChain;
    private final ActionExecutor actionExecutor;
    private final EmailParsingService emailParsingService;
    private final LabReportPrinter reportPrinter;
    private final TuningLlmScorer tuningLlmScorer;
    private final ReviewReportPrinter reviewReportPrinter;
    private final ReshuffleService reshuffleService;
    private final ReshuffleReportPrinter reshuffleReportPrinter;
    private final ReshuffleExecutor reshuffleExecutor;
    private final Whitelist tuningWhitelist;
    private final ApplicationContext applicationContext;

    public LabRunner(LabProperties labProperties,
                     Map<String, FilterDefinition> filterDefinitions,
                     FilterChain filterChain,
                     ActionExecutor actionExecutor,
                     EmailParsingService emailParsingService,
                     LabReportPrinter reportPrinter,
                     TuningLlmScorer tuningLlmScorer,
                     ReviewReportPrinter reviewReportPrinter,
                     ReshuffleService reshuffleService,
                     ReshuffleReportPrinter reshuffleReportPrinter,
                     ReshuffleExecutor reshuffleExecutor,
                     Whitelist tuningWhitelist,
                     ApplicationContext applicationContext) {
        this.labProperties = labProperties;
        this.filterDefinitions = filterDefinitions;
        this.filterChain = filterChain;
        this.actionExecutor = actionExecutor;
        this.emailParsingService = emailParsingService;
        this.reportPrinter = reportPrinter;
        this.tuningLlmScorer = tuningLlmScorer;
        this.reviewReportPrinter = reviewReportPrinter;
        this.reshuffleService = reshuffleService;
        this.reshuffleReportPrinter = reshuffleReportPrinter;
        this.reshuffleExecutor = reshuffleExecutor;
        this.tuningWhitelist = tuningWhitelist;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = labProperties.getRun().mode();
        switch (mode == null ? "filter" : mode.toLowerCase()) {
            case "review" -> runReview();
            case "reshuffle" -> runReshuffle();
            default -> runFilterChain();
        }
    }

    private void runFilterChain() {
        LabProperties.Run runConfig = labProperties.getRun();
        boolean dryRun = runConfig.dryRun();
        String folder = labProperties.getImap().folder();

        // 1. Resolve chain — accept comma-separated filter names
        List<String> chain = parseChain(runConfig.filter());
        if (chain.isEmpty()) {
            log.error("No filters specified in lab.run.filter");
            shutdown(1);
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String name : chain) {
            if (!filterDefinitions.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            log.error("Filter(s) {} not found in staging-filters.yml. Available: {}",
                    missing, filterDefinitions.keySet());
            shutdown(1);
            return;
        }

        // 2. Log session header
        printSessionHeader(chain, dryRun);

        // 3. Read emails
        Store store = null;
        Folder imapFolder = null;
        try {
            store = connectImap();
            imapFolder = store.getFolder(folder);
            imapFolder.open(dryRun ? Folder.READ_ONLY : Folder.READ_WRITE);

            Message[] messages = imapFolder.getMessages();
            // Newest first
            List<Message> messageList = new ArrayList<>(Arrays.asList(messages));
            java.util.Collections.reverse(messageList);

            // Apply limit
            int limit = runConfig.limit();
            if (limit > 0 && messageList.size() > limit) {
                messageList = messageList.subList(0, limit);
            }

            log.info("Found {} emails in '{}' (processing {})",
                    messages.length, folder, messageList.size());

            // 4. Process each email through the chain
            List<EmailMessage> emailMessages = new ArrayList<>();
            List<ChainResult> results = new ArrayList<>();
            int total = messageList.size();

            for (int i = 0; i < total; i++) {
                Message message = messageList.get(i);
                try {
                    EmailMessage emailMessage = emailParsingService.parse(message);
                    emailMessages.add(emailMessage);

                    ChainResult result = filterChain.run(chain, filterDefinitions, emailMessage);
                    results.add(result);

                    String subject = truncate(emailMessage.subject(), 50);
                    log.info("[{}/{}] \"{}\" → {}",
                            String.format("%02d", i + 1), String.format("%02d", total),
                            subject, formatActions(result));

                } catch (Exception e) {
                    log.error("[{}/{}] Failed to process email: {}",
                            String.format("%02d", i + 1), String.format("%02d", total), e.getMessage());
                    emailMessages.add(new EmailMessage("", "ERROR", "", List.of(),
                            "Processing failed: " + e.getMessage(), "", null, null, Map.of()));
                    results.add(new ChainResult(List.of(), List.of()));
                }
            }

            // 5. Print report
            String report = reportPrinter.buildReport(chain, filterDefinitions, folder, emailMessages, results);
            reportPrinter.printToConsole(report);
            reportPrinter.writeToFile(report, runConfig.reportFile());

            // 6. Execute actions if not dry-run
            if (!dryRun) {
                log.info("Executing IMAP actions (LIVE mode)...");
                for (int i = 0; i < results.size(); i++) {
                    ChainResult result = results.get(i);
                    Message message = messageList.get(i);
                    for (ResolvedAction action : result.actions()) {
                        actionExecutor.execute(action, message, store, false);
                    }
                }
            }

        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage(), e);
        } finally {
            closeQuietly(imapFolder);
            closeQuietly(store);
        }

        shutdown(0);
    }

    private void runReview() {
        LabProperties.Run runConfig = labProperties.getRun();
        String folder = labProperties.getImap().folder();
        String promptFile = labProperties.getTuning().promptFile();
        String promptTemplate;
        try {
            promptTemplate = loadPromptFile(promptFile);
        } catch (Exception e) {
            log.error("Failed to load prompt file '{}': {}", promptFile, e.getMessage());
            shutdown(1);
            return;
        }

        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Email Filter Lab — REVIEW");
        System.out.println("  Folder : " + folder);
        System.out.println("  Model  : " + labProperties.getOllama().model()
                + " @ " + labProperties.getOllama().baseUrl());
        System.out.println("  Prompt : " + promptFile);
        System.out.println("═══════════════════════════════════════════════");

        Store store = null;
        Folder imapFolder = null;
        try {
            store = connectImap();
            imapFolder = store.getFolder(folder);
            imapFolder.open(Folder.READ_ONLY);

            List<Message> messageList = orderedMessages(imapFolder, runConfig.limit());
            log.info("Found {} emails in '{}' (processing {})",
                    imapFolder.getMessageCount(), folder, messageList.size());

            List<ReviewEntry> entries = new ArrayList<>(messageList.size());
            for (int i = 0; i < messageList.size(); i++) {
                try {
                    EmailMessage parsed = emailParsingService.parse(messageList.get(i));
                    SubjectTagParser.ParsedTag tag = SubjectTagParser.parse(parsed.subject());
                    String cleanSubject = tag != null ? tag.cleanSubject() : parsed.subject();
                    String originalTag = tag != null
                            ? SubjectTagParser.buildTag(tag.pp(), tag.sa(), tag.llm(), tag.combined())
                            : "";

                    if (tuningWhitelist.isWhitelisted(parsed.from())) {
                        log.info("[{}/{}] WHITELISTED: {}", i + 1, messageList.size(), truncate(cleanSubject, 60));
                        entries.add(new ReviewEntry(parsed.from(), cleanSubject, originalTag, null, true));
                        continue;
                    }

                    EmailMessage cleanEmail = withSubject(parsed, cleanSubject);
                    log.info("[{}/{}] Scoring: {}", i + 1, messageList.size(), truncate(cleanSubject, 60));
                    TuningScoreResult result = tuningLlmScorer.score(cleanEmail, promptTemplate);
                    log.info("        Score: {} — {}", result.score(), truncate(result.reason(), 70));

                    entries.add(new ReviewEntry(parsed.from(), cleanSubject, originalTag, result, false));
                } catch (Exception e) {
                    log.error("[{}/{}] Failed to process email: {}", i + 1, messageList.size(), e.getMessage());
                }
            }

            reviewReportPrinter.print(entries, promptFile);

        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage(), e);
        } finally {
            closeQuietly(imapFolder);
            closeQuietly(store);
        }

        shutdown(0);
    }

    private void runReshuffle() {
        LabProperties.Run runConfig = labProperties.getRun();
        boolean dryRun = runConfig.dryRun();
        String folder = labProperties.getImap().folder();
        String promptFile = labProperties.getTuning().promptFile();
        String promptTemplate;
        try {
            promptTemplate = loadPromptFile(promptFile);
        } catch (Exception e) {
            log.error("Failed to load prompt file '{}': {}", promptFile, e.getMessage());
            shutdown(1);
            return;
        }

        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Email Filter Lab — RESHUFFLE");
        System.out.println("  Folder : " + folder);
        System.out.println("  Model  : " + labProperties.getOllama().model()
                + " @ " + labProperties.getOllama().baseUrl());
        System.out.println("  Prompt : " + promptFile);
        System.out.println("  Mode   : " + (dryRun ? "DRY-RUN" : "LIVE"));
        System.out.println("═══════════════════════════════════════════════");

        Store store = null;
        Folder imapFolder = null;
        try {
            store = connectImap();
            imapFolder = store.getFolder(folder);
            imapFolder.open(dryRun ? Folder.READ_ONLY : Folder.READ_WRITE);

            List<Message> messageList = orderedMessages(imapFolder, runConfig.limit());
            log.info("Found {} emails in '{}' (processing {})",
                    imapFolder.getMessageCount(), folder, messageList.size());

            List<EmailMessage> emails = new ArrayList<>(messageList.size());
            for (Message message : messageList) {
                try {
                    emails.add(emailParsingService.parse(message));
                } catch (Exception e) {
                    log.error("Failed to parse email, skipping: {}", e.getMessage());
                }
            }

            List<ReshufflePlan> plan = reshuffleService.buildPlan(emails, promptTemplate);
            reshuffleReportPrinter.print(plan, dryRun);

            if (!dryRun) {
                log.info("[LIVE] Executing reshuffle...");
                reshuffleExecutor.execute(plan, store, imapFolder);
                imapFolder = null; // executor closed/expunged it
            }

        } catch (MessagingException e) {
            log.error("IMAP error: {}", e.getMessage(), e);
        } finally {
            closeQuietly(imapFolder);
            closeQuietly(store);
        }

        shutdown(0);
    }

    private List<Message> orderedMessages(Folder folder, int limit) throws MessagingException {
        Message[] messages = folder.getMessages();
        List<Message> messageList = new ArrayList<>(Arrays.asList(messages));
        java.util.Collections.reverse(messageList);
        if (limit > 0 && messageList.size() > limit) {
            messageList = messageList.subList(0, limit);
        }
        return messageList;
    }

    private EmailMessage withSubject(EmailMessage email, String subject) {
        return new EmailMessage(email.messageId(), email.from(), email.fromName(), email.to(),
                subject, email.bodyText(), email.bodyHtml(), email.rawMessage(), email.headers());
    }

    private String loadPromptFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "Prompt file not found: " + path + " — set lab.tuning.prompt-file in application.yml");
        }
        return Files.readString(file.toPath());
    }

    static List<String> parseChain(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private Store connectImap() throws MessagingException {
        LabProperties.Imap imap = labProperties.getImap();
        Properties props = new Properties();
        String protocol = imap.ssl() ? "imaps" : "imap";
        props.setProperty("mail.store.protocol", protocol);
        props.setProperty("mail." + protocol + ".host", imap.host());
        props.setProperty("mail." + protocol + ".port", String.valueOf(imap.port()));
        props.setProperty("mail." + protocol + ".timeout", "30000");
        props.setProperty("mail." + protocol + ".connectiontimeout", "15000");
        props.setProperty("mail." + protocol + ".usesocketchannels", "false");
        if (imap.ssl()) {
            props.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
            props.setProperty("mail." + protocol + ".ssl.protocols", "TLSv1.2 TLSv1.3");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(imap.host(), imap.username(), imap.password());
        log.info("Connected to IMAP: {}@{}", imap.username(), imap.host());
        return store;
    }

    private void printSessionHeader(List<String> chain, boolean dryRun) {
        String mode = dryRun ? "DRY-RUN" : "LIVE";
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Email Filter Lab");
        if (chain.size() == 1) {
            String name = chain.get(0);
            System.out.println("  Filter : " + name + " [" + filterDefinitions.get(name).type() + "]");
        } else {
            System.out.println("  Chain  : " + String.join(" → ", chain));
        }
        System.out.println("  Folder : " + labProperties.getImap().folder());
        System.out.println("  Model  : " + labProperties.getOllama().model()
                + " @ " + labProperties.getOllama().baseUrl());
        System.out.println("  Mode   : " + mode);
        System.out.println("═══════════════════════════════════════════════");
    }

    private void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException e) {
                log.debug("Error closing folder: {}", e.getMessage());
            }
        }
    }

    private void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                log.debug("Error closing store: {}", e.getMessage());
            }
        }
    }

    private String formatActions(ChainResult result) {
        if (result.actions().isEmpty()) {
            return "LEAVE";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.actions().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ResolvedAction a = result.actions().get(i);
            sb.append(a.type());
            if (a.type() == ActionType.MOVE_TO_FOLDER && a.targetFolder() != null) {
                sb.append("(").append(a.targetFolder()).append(")");
            } else if (a.type() == ActionType.SEND_EMAIL && a.emailTo() != null) {
                sb.append("(").append(a.emailTo()).append(")");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void shutdown(int exitCode) {
        SpringApplication.exit(applicationContext, () -> exitCode);
    }
}
