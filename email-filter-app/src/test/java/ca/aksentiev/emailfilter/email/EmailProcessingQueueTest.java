package ca.aksentiev.emailfilter.email;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.ProcessingProperties;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.FilterChainDispatcher;
import ca.aksentiev.emailfilter.filter.FilterResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class EmailProcessingQueueTest {

    private EmailProcessingQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void enqueueAndConsumeSingleEmail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EmailMessage> dispatched = new AtomicReference<>();

        FilterChainDispatcher dispatcher = mockDispatcher((message, account) -> {
            dispatched.set(message);
            latch.countDown();
            return FilterResult.processed(0.0, "leave", "All filters passed");
        });

        queue = new EmailProcessingQueue(new ProcessingProperties(1, 5000), dispatcher, mock(ca.aksentiev.emailfilter.action.EmailActionService.class));
        queue.start();

        queue.enqueue(queuedEmail("Test Subject"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatched.get().subject()).isEqualTo("Test Subject");
    }

    @Test
    void multipleConsumerThreadsProcessConcurrently() throws Exception {
        int emailCount = 10;
        CountDownLatch latch = new CountDownLatch(emailCount);

        FilterChainDispatcher dispatcher = mockDispatcher((message, account) -> {
            latch.countDown();
            return FilterResult.processed(0.0, "leave", "All filters passed");
        });

        queue = new EmailProcessingQueue(new ProcessingProperties(3, 5000), dispatcher, mock(ca.aksentiev.emailfilter.action.EmailActionService.class));
        queue.start();

        for (int i = 0; i < emailCount; i++) {
            queue.enqueue(queuedEmail("Email " + i));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void queueSizeReflectsEnqueuedItems() {
        FilterChainDispatcher dispatcher = mock(FilterChainDispatcher.class);
        ca.aksentiev.emailfilter.action.EmailActionService actionService = mock(ca.aksentiev.emailfilter.action.EmailActionService.class);

        queue = new EmailProcessingQueue(new ProcessingProperties(1, 5000), dispatcher, actionService);
        // Don't start consumers — just test the queue size
        assertThat(queue.size()).isZero();

        queue.enqueue(queuedEmail("First"));
        queue.enqueue(queuedEmail("Second"));
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void dispatcherExceptionDoesNotKillConsumerThread() throws Exception {
        CountDownLatch secondLatch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();

        FilterChainDispatcher dispatcher = mockDispatcher((message, account) -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                throw new RuntimeException("Simulated filter failure");
            }
            secondLatch.countDown();
            return FilterResult.processed(0.0, "leave", "pass");
        });

        queue = new EmailProcessingQueue(new ProcessingProperties(1, 5000), dispatcher, mock(ca.aksentiev.emailfilter.action.EmailActionService.class));
        queue.start();

        queue.enqueue(queuedEmail("Will fail"));
        queue.enqueue(queuedEmail("Will succeed"));

        assertThat(secondLatch.await(5, TimeUnit.SECONDS))
                .as("Consumer should survive exception and process second email")
                .isTrue();
    }

    @Test
    void gracefulShutdownStopsConsumers() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        FilterChainDispatcher dispatcher = mockDispatcher((message, account) -> {
            started.countDown();
            return FilterResult.processed(0.0, "leave", "pass");
        });

        queue = new EmailProcessingQueue(new ProcessingProperties(2, 5000), dispatcher, mock(ca.aksentiev.emailfilter.action.EmailActionService.class));
        queue.start();

        queue.enqueue(queuedEmail("Trigger start"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // Shutdown should complete without hanging
        queue.shutdown();
        queue = null; // prevent double-shutdown in tearDown
    }

    @Test
    void accountContextPassedToDispatcher() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AccountProperties.Account> dispatchedAccount = new AtomicReference<>();

        FilterChainDispatcher dispatcher = mockDispatcher((message, account) -> {
            dispatchedAccount.set(account);
            latch.countDown();
            return FilterResult.processed(0.0, "leave", "pass");
        });

        queue = new EmailProcessingQueue(new ProcessingProperties(1, 5000), dispatcher, mock(ca.aksentiev.emailfilter.action.EmailActionService.class));
        queue.start();

        AccountProperties.Account account = testAccount("work-account");
        queue.enqueue(new QueuedEmail(testMessage("Subject"), account));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatchedAccount.get().getName()).isEqualTo("work-account");
    }

    /**
     * Creates a mock FilterChainDispatcher that delegates to the given function.
     */
    private FilterChainDispatcher mockDispatcher(DispatchFunction fn) {
        FilterChainDispatcher dispatcher = mock(FilterChainDispatcher.class);
        doAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            AccountProperties.Account account = invocation.getArgument(1);
            return fn.dispatch(message, account);
        }).when(dispatcher).dispatch(any(EmailMessage.class), any(AccountProperties.Account.class));
        return dispatcher;
    }

    @FunctionalInterface
    private interface DispatchFunction {
        FilterResult dispatch(EmailMessage message, AccountProperties.Account account);
    }

    private QueuedEmail queuedEmail(String subject) {
        return new QueuedEmail(testMessage(subject), testAccount("test-account"));
    }

    private EmailMessage testMessage(String subject) {
        return new EmailMessage(
                "<test@example.com>", "sender@example.com", "Sender",
                List.of("me@example.com"), subject, "Body text", "",
                null, Map.of());
    }

    private AccountProperties.Account testAccount(String name) {
        return new AccountProperties.Account(
                name, "imap.example.com", "user@example.com", "pass",
                List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));
    }
}
