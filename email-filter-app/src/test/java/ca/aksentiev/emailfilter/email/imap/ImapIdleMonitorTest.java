package ca.aksentiev.emailfilter.email.imap;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ImapProperties;
import ca.aksentiev.emailfilter.email.EmailProcessingQueue;
import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class ImapIdleMonitorTest {

    private final ImapProperties imapProperties = new ImapProperties();
    private final ImapConnectionFactory connectionFactory = new ImapConnectionFactory(imapProperties);

    @Mock
    private EmailParsingService parsingService;

    @Mock
    private EmailProcessingQueue processingQueue;

    @Test
    void startWithNoAccountsDoesNotThrow() {
        AccountProperties props = new AccountProperties(List.of());
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, connectionFactory, parsingService, processingQueue);

        assertThatCode(monitor::start).doesNotThrowAnyException();

        monitor.shutdown();
    }

    @Test
    void startWithNullAccountsDoesNotThrow() {
        AccountProperties props = new AccountProperties(null);
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, connectionFactory, parsingService, processingQueue);

        assertThatCode(monitor::start).doesNotThrowAnyException();

        monitor.shutdown();
    }

    @Test
    void shutdownBeforeStartDoesNotThrow() {
        AccountProperties props = new AccountProperties(List.of());
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, connectionFactory, parsingService, processingQueue);

        assertThatCode(monitor::shutdown).doesNotThrowAnyException();
    }

    @Test
    void startCreatesThreadPerAccount() throws Exception {
        AccountProperties.Account account1 = new AccountProperties.Account(
                "unit-acct-a", "imap.invalid", "user1@example.com", "pass1",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));
        AccountProperties.Account account2 = new AccountProperties.Account(
                "unit-acct-b", "imap.invalid", "user2@example.com", "pass2",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));

        AccountProperties props = new AccountProperties(List.of(account1, account2));
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, connectionFactory, parsingService, processingQueue);

        monitor.start();

        try {
            Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                long idleThreadCount = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().equals("imap-idle-unit-acct-a")
                                || t.getName().equals("imap-idle-unit-acct-b"))
                        .count();
                assertThat(idleThreadCount).isEqualTo(2);
            });
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
        ImapIdleMonitor monitor = new ImapIdleMonitor(props, imapProperties, connectionFactory, parsingService, processingQueue);

        monitor.start();

        Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("imap-idle-test"))
                    .count();
            assertThat(count).isEqualTo(1);
        });

        monitor.shutdown();

        Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("imap-idle-test"))
                    .filter(Thread::isAlive)
                    .count();
            assertThat(count).isZero();
        });
    }
}
