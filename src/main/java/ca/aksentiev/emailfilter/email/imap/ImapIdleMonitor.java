package ca.aksentiev.emailfilter.email.imap;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ImapProperties;
import ca.aksentiev.emailfilter.email.EmailProcessingQueue;
import ca.aksentiev.emailfilter.email.QueuedEmail;
import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Monitors IMAP mailboxes using IDLE for real-time email arrival notifications.
 * <p>
 * One daemon thread per configured account. Each thread:
 * <ol>
 *   <li>Connects to IMAP with IMAPS (port 993)</li>
 *   <li>Opens the inbox folder</li>
 *   <li>Registers a message listener that parses new emails and enqueues them</li>
 *   <li>Enters IDLE, re-issuing before the 29-minute RFC 2177 timeout</li>
 *   <li>Reconnects with exponential backoff on connection failure</li>
 * </ol>
 * Graceful shutdown closes all IMAP connections and stops threads.
 */
@Service
public class ImapIdleMonitor {

    private static final Logger log = LoggerFactory.getLogger(ImapIdleMonitor.class);

    private final AccountProperties accountProperties;
    private final ImapProperties imapProperties;
    private final EmailParsingService parsingService;
    private final EmailProcessingQueue processingQueue;
    private final List<Thread> idleThreads = new ArrayList<>();
    private volatile boolean running;

    public ImapIdleMonitor(
            AccountProperties accountProperties,
            ImapProperties imapProperties,
            EmailParsingService parsingService,
            EmailProcessingQueue processingQueue) {
        this.accountProperties = accountProperties;
        this.imapProperties = imapProperties;
        this.parsingService = parsingService;
        this.processingQueue = processingQueue;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        List<AccountProperties.Account> accounts = accountProperties.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            log.warn("No IMAP accounts configured, IDLE monitor will not start");
            return;
        }

        running = true;
        for (AccountProperties.Account account : accounts) {
            Thread thread = new Thread(() -> monitorAccount(account), "imap-idle-" + account.getName());
            thread.setDaemon(true);
            thread.start();
            idleThreads.add(thread);
            log.info("Started IMAP IDLE thread for account '{}'", account.getName());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down IMAP IDLE monitor ({} threads)", idleThreads.size());
        running = false;
        for (Thread thread : idleThreads) {
            thread.interrupt();
        }
        for (Thread thread : idleThreads) {
            try {
                thread.join(imapProperties.getShutdownTimeout());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for IDLE thread '{}' to finish", thread.getName());
            }
        }
        log.info("IMAP IDLE monitor shut down");
    }

    private void monitorAccount(AccountProperties.Account account) {
        long backoff = imapProperties.getInitialBackoff();

        while (running) {
            Store store = null;
            IMAPFolder folder = null;
            try {
                store = connect(account);
                folder = openInbox(store, account);
                backoff = imapProperties.getInitialBackoff();

                log.info("Connected to IMAP for account '{}', entering IDLE loop", account.getName());
                idleLoop(folder, account);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("IMAP connection failed for account '{}': {}", account.getName(), e.getMessage());
                log.debug("IMAP connection error details for account '{}'", account.getName(), e);
            } finally {
                closeQuietly(folder, store, account.getName());
            }

            if (!running) {
                break;
            }

            log.info("Reconnecting to account '{}' in {} ms", account.getName(), backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, imapProperties.getMaxBackoff());
        }

        log.debug("IDLE thread for account '{}' exiting", account.getName());
    }

    private Store connect(AccountProperties.Account account) throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imaps");
        props.setProperty("mail.imaps.host", account.getHost());
        props.setProperty("mail.imaps.port", String.valueOf(imapProperties.getPort()));
        props.setProperty("mail.imaps.timeout", String.valueOf(imapProperties.getSocketTimeout()));
        props.setProperty("mail.imaps.connectiontimeout", String.valueOf(imapProperties.getConnectionTimeout()));
        // Enable IDLE support
        props.setProperty("mail.imaps.usesocketchannels", "true");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(account.getHost(), account.getUsername(), account.getPassword());
        return store;
    }

    private IMAPFolder openInbox(Store store, AccountProperties.Account account) throws MessagingException {
        String inboxName = account.getFolders().inbox();
        Folder folder = store.getFolder(inboxName);
        if (!folder.exists()) {
            throw new MessagingException("Inbox folder '" + inboxName + "' does not exist for account '"
                    + account.getName() + "'");
        }
        folder.open(Folder.READ_WRITE);

        if (!(folder instanceof IMAPFolder)) {
            throw new MessagingException("Folder is not an IMAPFolder — IDLE not supported for account '"
                    + account.getName() + "'");
        }

        IMAPFolder imapFolder = (IMAPFolder) folder;
        imapFolder.addMessageCountListener(new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent event) {
                handleNewMessages(event.getMessages(), account);
            }
        });

        return imapFolder;
    }

    private void idleLoop(IMAPFolder folder, AccountProperties.Account account) throws MessagingException {
        while (running && folder.isOpen()) {
            // Schedule a thread to break IDLE before the 29-minute RFC timeout
            Thread keepAlive = new Thread(() -> {
                try {
                    Thread.sleep(imapProperties.getIdleInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (folder.isOpen()) {
                        // NOOP forces the server to return from IDLE
                        folder.doCommand(p -> {
                            p.simpleCommand("NOOP", null);
                            return null;
                        });
                    }
                } catch (MessagingException e) {
                    log.debug("Keep-alive NOOP failed for account '{}': {}", account.getName(), e.getMessage());
                }
            }, "imap-keepalive-" + account.getName());
            keepAlive.setDaemon(true);
            keepAlive.start();

            try {
                folder.idle();
            } finally {
                keepAlive.interrupt();
            }
        }
    }

    private void handleNewMessages(Message[] messages, AccountProperties.Account account) {
        for (Message message : messages) {
            try {
                EmailMessage parsed = parsingService.parse(message);
                processingQueue.enqueue(new QueuedEmail(parsed, account));
                log.info("Enqueued new email '{}' from '{}' for account '{}'",
                        parsed.subject(), parsed.from(), account.getName());
            } catch (Exception e) {
                log.error("Failed to parse/enqueue email for account '{}': {}",
                        account.getName(), e.getMessage(), e);
            }
        }
    }

    private void closeQuietly(IMAPFolder folder, Store store, String accountName) {
        if (folder != null) {
            try {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            } catch (MessagingException e) {
                log.debug("Error closing folder for account '{}': {}", accountName, e.getMessage());
            }
        }
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
}
