package ca.aksentiev.emailfilter.email.imap;

import java.util.List;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ImapProperties;
import ca.aksentiev.emailfilter.email.EmailProcessingQueue;
import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class ImapIdleMonitorTest {

    private final ImapProperties imapProperties = new ImapProperties();

    @Mock
    private EmailParsingService parsingService;

    @Mock
    private EmailProcessingQueue processingQueue;

    @Test
    void startWithNoAccountsDoesNotThrow() {
        AccountProperties props = new AccountProperties(List.of());
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, parsingService, processingQueue);

        assertThatCode(monitor::start).doesNotThrowAnyException();

        monitor.shutdown();
    }

    @Test
    void startWithNullAccountsDoesNotThrow() {
        AccountProperties props = new AccountProperties(null);
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, parsingService, processingQueue);

        assertThatCode(monitor::start).doesNotThrowAnyException();

        monitor.shutdown();
    }

    @Test
    void shutdownBeforeStartDoesNotThrow() {
        AccountProperties props = new AccountProperties(List.of());
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, parsingService, processingQueue);

        assertThatCode(monitor::shutdown).doesNotThrowAnyException();
    }

    @Test
    void startCreatesThreadPerAccount() throws Exception {
        AccountProperties.Account account1 = new AccountProperties.Account(
                "personal", "imap.invalid", "user1@example.com", "pass1",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));
        AccountProperties.Account account2 = new AccountProperties.Account(
                "work", "imap.invalid", "user2@example.com", "pass2",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));

        AccountProperties props = new AccountProperties(List.of(account1, account2));
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, parsingService, processingQueue);

        monitor.start();

        // Give threads a moment to start (they'll fail to connect and enter backoff)
        Thread.sleep(200);

        // Verify threads were created by checking for the exact thread names this test owns
        long idleThreadCount = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("imap-idle-personal")
                        || t.getName().equals("imap-idle-work"))
                .count();

        try {
            // Should have created 2 threads (one per account)
            org.assertj.core.api.Assertions.assertThat(idleThreadCount).isEqualTo(2);
        } finally {
            monitor.shutdown();
        }
    }

    @Test
    void shutdownStopsIdleThreads() throws Exception {
        AccountProperties.Account account = new AccountProperties.Account(
                "test", "imap.invalid", "user@example.com", "pass",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));

        AccountProperties props = new AccountProperties(List.of(account));
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, parsingService, processingQueue);

        monitor.start();
        Thread.sleep(200);

        monitor.shutdown();
        Thread.sleep(200);

        long idleThreadCount = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("imap-idle-test"))
                .filter(Thread::isAlive)
                .count();

        org.assertj.core.api.Assertions.assertThat(idleThreadCount).isZero();
    }
}
