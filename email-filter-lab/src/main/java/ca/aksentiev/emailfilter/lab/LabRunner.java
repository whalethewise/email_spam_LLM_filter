package ca.aksentiev.emailfilter.lab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.lab.config.LabProperties;
import ca.aksentiev.emailfilter.lab.engine.ActionExecutor;
import ca.aksentiev.emailfilter.lab.engine.ActionType;
import ca.aksentiev.emailfilter.lab.engine.FilterDefinition;
import ca.aksentiev.emailfilter.lab.engine.FilterDispatcher;
import ca.aksentiev.emailfilter.lab.engine.FilterResult;
import ca.aksentiev.emailfilter.lab.engine.ResolvedAction;
import ca.aksentiev.emailfilter.lab.engine.VariableResolver;
import ca.aksentiev.emailfilter.lab.report.LabReportPrinter;
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
 * Main orchestrator for a lab session.
 * Reads emails from an IMAP folder, runs a single filter, produces a report.
 */
@Component
public class LabRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LabRunner.class);

    private final LabProperties labProperties;
    private final Map<String, FilterDefinition> filterDefinitions;
    private final FilterDispatcher filterDispatcher;
    private final ActionExecutor actionExecutor;
    private final VariableResolver variableResolver;
    private final EmailParsingService emailParsingService;
    private final LabReportPrinter reportPrinter;
    private final ApplicationContext applicationContext;

    public LabRunner(LabProperties labProperties,
                     Map<String, FilterDefinition> filterDefinitions,
                     FilterDispatcher filterDispatcher,
                     ActionExecutor actionExecutor,
                     VariableResolver variableResolver,
                     EmailParsingService emailParsingService,
                     LabReportPrinter reportPrinter,
                     ApplicationContext applicationContext) {
        this.labProperties = labProperties;
        this.filterDefinitions = filterDefinitions;
        this.filterDispatcher = filterDispatcher;
        this.actionExecutor = actionExecutor;
        this.variableResolver = variableResolver;
        this.emailParsingService = emailParsingService;
        this.reportPrinter = reportPrinter;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        LabProperties.Run runConfig = labProperties.getRun();
        String filterName = runConfig.filter();
        boolean dryRun = runConfig.dryRun();
        String folder = labProperties.getImap().folder();

        // 1. Resolve filter
        FilterDefinition filter = filterDefinitions.get(filterName);
        if (filter == null) {
            log.error("Filter '{}' not found in staging-filters.yml. Available: {}",
                    filterName, filterDefinitions.keySet());
            shutdown(1);
            return;
        }

        // 2. Log session header
        printSessionHeader(filterName, filter, dryRun);

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

            // 4. Process each email
            List<EmailMessage> emailMessages = new ArrayList<>();
            List<FilterResult> results = new ArrayList<>();
            int total = messageList.size();

            for (int i = 0; i < total; i++) {
                Message message = messageList.get(i);
                try {
                    EmailMessage emailMessage = emailParsingService.parse(message);
                    emailMessages.add(emailMessage);

                    FilterResult result = filterDispatcher.dispatch(filter, emailMessage);
                    results.add(result);

                    String subject = truncate(emailMessage.subject(), 50);
                    log.info("[{}/{}] \"{}\" → {} (score: {})",
                            String.format("%02d", i + 1), String.format("%02d", total),
                            subject, formatActions(result), result.score());

                } catch (Exception e) {
                    log.error("[{}/{}] Failed to process email: {}",
                            String.format("%02d", i + 1), String.format("%02d", total), e.getMessage());
                    // Add a placeholder so indices stay aligned
                    emailMessages.add(new EmailMessage("", "ERROR", "", List.of(),
                            "Processing failed: " + e.getMessage(), "", null, null, Map.of()));
                    results.add(FilterResult.leave("error: " + e.getMessage()));
                }
            }

            // 5. Print report
            String report = reportPrinter.buildReport(filterName, filter, folder, emailMessages, results);
            reportPrinter.printToConsole(report);
            reportPrinter.writeToFile(report, runConfig.reportFile());

            // 6. Execute actions if not dry-run
            if (!dryRun) {
                log.info("Executing IMAP actions (LIVE mode)...");
                for (int i = 0; i < results.size(); i++) {
                    FilterResult result = results.get(i);
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

    private void printSessionHeader(String filterName, FilterDefinition filter, boolean dryRun) {
        String mode = dryRun ? "DRY-RUN" : "LIVE";
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Email Filter Lab");
        System.out.println("  Filter : " + filterName + " [" + filter.type() + "]");
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

    private String formatActions(FilterResult result) {
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
