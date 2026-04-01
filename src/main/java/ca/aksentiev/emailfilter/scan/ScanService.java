package ca.aksentiev.emailfilter.scan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ScanProperties;
import ca.aksentiev.emailfilter.email.EmailProcessingQueue;
import ca.aksentiev.emailfilter.email.QueuedEmail;
import ca.aksentiev.emailfilter.email.imap.ImapConnectionFactory;
import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scans existing messages in IMAP folders and enqueues them for processing.
 * <p>
 * Unlike {@link ca.aksentiev.emailfilter.email.imap.ImapIdleMonitor} which
 * monitors for new arrivals, this service reads messages already in the
 * mailbox — newest first. Always forces dry-run mode regardless of the
 * global dry-run setting, so a scan never moves or deletes emails.
 * <p>
 * Triggered on demand via the management API ({@code POST /api/scan}).
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final AccountProperties accountProperties;
    private final ScanProperties scanProperties;
    private final ImapConnectionFactory connectionFactory;
    private final EmailParsingService parsingService;
    private final EmailProcessingQueue processingQueue;
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public ScanService(
            AccountProperties accountProperties,
            ScanProperties scanProperties,
            ImapConnectionFactory connectionFactory,
            EmailParsingService parsingService,
            EmailProcessingQueue processingQueue) {
        this.accountProperties = accountProperties;
        this.scanProperties = scanProperties;
        this.connectionFactory = connectionFactory;
        this.parsingService = parsingService;
        this.processingQueue = processingQueue;
    }

    /**
     * Initiates a scan across all configured accounts.
     *
     * @return summary of the scan result
     * @throws IllegalStateException if a scan is already in progress
     */
    public ScanResult scan() {
        if (!scanProperties.enabled()) {
            return new ScanResult(0, 0, 0, "Scan is disabled in configuration");
        }

        if (!scanning.compareAndSet(false, true)) {
            throw new IllegalStateException("A scan is already in progress");
        }

        try {
            return doScan();
        } finally {
            scanning.set(false);
        }
    }

    public boolean isScanning() {
        return scanning.get();
    }

    private ScanResult doScan() {
        List<AccountProperties.Account> accounts = accountProperties.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            return new ScanResult(0, 0, 0, "No IMAP accounts configured");
        }

        int totalEnqueued = 0;
        int totalFailed = 0;
        int totalAccounts = 0;

        for (AccountProperties.Account account : accounts) {
            totalAccounts++;
            Store store = null;
            try {
                store = connectionFactory.connect(account);
                AccountScanCounts counts = scanAccount(store, account);
                totalEnqueued += counts.enqueued();
                totalFailed += counts.failed();
            } catch (MessagingException e) {
                log.error("Failed to connect to account '{}' for scan: {}",
                        account.getName(), e.getMessage());
                totalFailed++;
            } finally {
                closeQuietly(store, account.getName());
            }
        }

        String message = String.format(
                "Scan complete: %d account(s), %d email(s) enqueued, %d failure(s)",
                totalAccounts, totalEnqueued, totalFailed);
        log.info(message);
        return new ScanResult(totalAccounts, totalEnqueued, totalFailed, message);
    }

    private AccountScanCounts scanAccount(Store store, AccountProperties.Account account)
            throws MessagingException {
        if (scanProperties.inboxOnly()) {
            return scanFolder(store, account.getFolders().inbox(), account);
        }

        int enqueued = 0;
        int failed = 0;
        Folder[] folders = store.getDefaultFolder().list("*");
        for (Folder folder : folders) {
            if (!isSelectable(folder)) {
                continue;
            }
            AccountScanCounts counts = scanFolder(store, folder.getFullName(), account);
            enqueued += counts.enqueued();
            failed += counts.failed();
        }
        return new AccountScanCounts(enqueued, failed);
    }

    private AccountScanCounts scanFolder(Store store, String folderName, AccountProperties.Account account)
            throws MessagingException {
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) {
            log.warn("Folder '{}' does not exist for account '{}', skipping",
                    folderName, account.getName());
            return new AccountScanCounts(0, 0);
        }

        folder.open(Folder.READ_ONLY);
        try {
            int messageCount = folder.getMessageCount();
            if (messageCount == 0) {
                log.debug("Folder '{}' for account '{}' is empty", folderName, account.getName());
                return new AccountScanCounts(0, 0);
            }

            // Newest first: fetch from end of folder backwards
            int limit = scanProperties.limit();
            int startIndex = (limit > 0 && limit < messageCount) ? messageCount - limit + 1 : 1;
            Message[] messages = folder.getMessages(startIndex, messageCount);

            // Reverse so newest is processed first
            List<Message> reversed = new ArrayList<>(Arrays.asList(messages));
            java.util.Collections.reverse(reversed);

            log.info("Scanning {} message(s) from folder '{}' for account '{}' (total in folder: {})",
                    reversed.size(), folderName, account.getName(), messageCount);

            int enqueued = 0;
            int failed = 0;
            for (Message message : reversed) {
                try {
                    EmailMessage parsed = parsingService.parse(message);
                    processingQueue.enqueue(new QueuedEmail(parsed, account, true));
                    enqueued++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to parse message #{} in folder '{}' for account '{}': {}",
                            message.getMessageNumber(), folderName, account.getName(), e.getMessage());
                    log.debug("Parse failure details", e);
                }
            }

            return new AccountScanCounts(enqueued, failed);
        } finally {
            try {
                folder.close(false);
            } catch (MessagingException e) {
                log.debug("Error closing folder '{}': {}", folderName, e.getMessage());
            }
        }
    }

    private boolean isSelectable(Folder folder) throws MessagingException {
        return (folder.getType() & Folder.HOLDS_MESSAGES) != 0;
    }

    private void closeQuietly(Store store, String accountName) {
        if (store != null) {
            try {
                if (store.isConnected()) {
                    store.close();
                }
            } catch (MessagingException e) {
                log.debug("Error closing store for account '{}': {}", accountName, e.getMessage());
            }
        }
    }

    private record AccountScanCounts(int enqueued, int failed) {}
}
