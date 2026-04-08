package ca.aksentiev.emailfilter.scan;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.DryRunProperties;
import ca.aksentiev.emailfilter.config.ScanProperties;
import ca.aksentiev.emailfilter.email.EmailProcessingQueue;
import ca.aksentiev.emailfilter.email.QueuedEmail;
import ca.aksentiev.emailfilter.email.imap.ImapConnectionFactory;
import ca.aksentiev.emailfilter.email.parser.EmailParsingService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScanServiceTest {

    @Mock
    private ImapConnectionFactory connectionFactory;

    @Mock
    private EmailParsingService parsingService;

    @Mock
    private EmailProcessingQueue processingQueue;

    @Test
    void scanWhenDisabledReturnsDisabledResult() {
        ScanProperties props = new ScanProperties(false, true, 0, 50);
        AccountProperties accountProps = new AccountProperties(List.of());
        ScanService service = new ScanService(accountProps, props, new DryRunProperties(true, null, null, null), connectionFactory, parsingService, processingQueue);

        ScanResult result = service.scan();

        assertThat(result.emailsEnqueued()).isZero();
        assertThat(result.message()).contains("disabled");
        verify(processingQueue, never()).enqueue(any(QueuedEmail.class));
    }

    @Test
    void scanWithNoAccountsReturnsEmptyResult() {
        ScanProperties props = new ScanProperties(true, true, 0, 50);
        AccountProperties accountProps = new AccountProperties(List.of());
        ScanService service = new ScanService(accountProps, props, new DryRunProperties(true, null, null, null), connectionFactory, parsingService, processingQueue);

        ScanResult result = service.scan();

        assertThat(result.accountsScanned()).isZero();
        assertThat(result.emailsEnqueued()).isZero();
        assertThat(result.message()).contains("No IMAP accounts");
    }

    @Test
    void scanWithNullAccountsReturnsEmptyResult() {
        ScanProperties props = new ScanProperties(true, true, 0, 50);
        AccountProperties accountProps = new AccountProperties(null);
        ScanService service = new ScanService(accountProps, props, new DryRunProperties(true, null, null, null), connectionFactory, parsingService, processingQueue);

        ScanResult result = service.scan();

        assertThat(result.accountsScanned()).isZero();
        assertThat(result.message()).contains("No IMAP accounts");
    }

    @Test
    void concurrentScanThrowsIllegalState() throws Exception {
        ScanProperties props = new ScanProperties(true, true, 0, 50);
        AccountProperties.Account account = new AccountProperties.Account(
                "test", "imap.invalid", "user@example.com", "pass",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));
        AccountProperties accountProps = new AccountProperties(List.of(account));

        CountDownLatch connectCalled = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);

        // Mock connectionFactory.connect() to block until we release it
        doAnswer(invocation -> {
            connectCalled.countDown();
            releaseConnect.await(10, TimeUnit.SECONDS);
            throw new MessagingException("Released");
        }).when(connectionFactory).connect(any());

        ScanService service = new ScanService(
                accountProps, props, new DryRunProperties(true, null, null, null), connectionFactory, parsingService, processingQueue);

        Thread scanThread = new Thread(service::scan, "scan-test");
        scanThread.start();

        // Wait until the first scan is inside connect()
        assertThat(connectCalled.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(service::scan)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already in progress");
        } finally {
            releaseConnect.countDown();
            scanThread.join(5000);
        }
    }

    @Test
    void isScanningReturnsFalseInitially() {
        ScanProperties props = new ScanProperties(true, true, 0, 50);
        AccountProperties accountProps = new AccountProperties(List.of());
        ScanService service = new ScanService(accountProps, props, new DryRunProperties(true, null, null, null), connectionFactory, parsingService, processingQueue);

        assertThat(service.isScanning()).isFalse();
    }
}
