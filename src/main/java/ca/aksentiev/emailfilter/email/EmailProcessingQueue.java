package ca.aksentiev.emailfilter.email;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ca.aksentiev.emailfilter.config.ProcessingProperties;
import ca.aksentiev.emailfilter.filter.FilterChainDispatcher;
import ca.aksentiev.emailfilter.filter.FilterResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Shared processing queue for incoming emails.
 * <p>
 * IMAP monitors enqueue {@link QueuedEmail} items. Consumer threads
 * dequeue and dispatch through the filter chain. The number of consumer
 * threads is configurable via {@code emailfilter.processing.consumer-threads}.
 */
@Service
public class EmailProcessingQueue {

    private static final Logger log = LoggerFactory.getLogger(EmailProcessingQueue.class);

    private final LinkedBlockingQueue<QueuedEmail> queue = new LinkedBlockingQueue<>();
    private final FilterChainDispatcher dispatcher;
    private final int consumerThreadCount;
    private final long shutdownTimeoutMs;
    private final List<Thread> consumerThreads = new ArrayList<>();
    private volatile boolean running;

    public EmailProcessingQueue(ProcessingProperties properties, FilterChainDispatcher dispatcher) {
        this.consumerThreadCount = properties.consumerThreads();
        this.shutdownTimeoutMs = properties.shutdownTimeoutMs();
        this.dispatcher = dispatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        for (int i = 0; i < consumerThreadCount; i++) {
            Thread thread = new Thread(this::consumeLoop, "email-consumer-" + i);
            thread.setDaemon(true);
            thread.start();
            consumerThreads.add(thread);
        }
        log.info("Started {} email processing consumer thread(s)", consumerThreadCount);
    }

    /**
     * Enqueues an email for processing.
     *
     * @param item the email + account context to process
     */
    public void enqueue(QueuedEmail item) {
        queue.add(item);
        log.debug("Enqueued email '{}' from account '{}' (queue size: {})",
                item.message().subject(), item.account().getName(), queue.size());
    }

    /**
     * Returns the current number of items waiting in the queue.
     */
    public int size() {
        return queue.size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down email processing queue ({} items remaining)", queue.size());
        running = false;
        for (Thread thread : consumerThreads) {
            thread.interrupt();
        }
        for (Thread thread : consumerThreads) {
            try {
                thread.join(shutdownTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for consumer thread '{}' to finish", thread.getName());
            }
        }
        log.info("Email processing queue shut down");
    }

    private void consumeLoop() {
        log.debug("Consumer thread '{}' started", Thread.currentThread().getName());
        while (running) {
            try {
                QueuedEmail item = queue.poll(1, TimeUnit.SECONDS);
                if (item != null) {
                    processEmail(item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running) {
                    log.warn("Consumer thread '{}' interrupted unexpectedly", Thread.currentThread().getName());
                }
            }
        }
        log.debug("Consumer thread '{}' stopped", Thread.currentThread().getName());
    }

    private void processEmail(QueuedEmail item) {
        String subject = item.message().subject();
        String accountName = item.account().getName();
        String mode = item.forceDryRun() ? "SCAN/DRY-RUN" : "LIVE";
        try {
            log.debug("[{}] Processing email '{}' for account '{}'", mode, subject, accountName);
            FilterResult result = dispatcher.dispatch(item.message(), item.account());
            log.info("[{}] Processed email '{}' for account '{}': action={} reason='{}'",
                    mode, subject, accountName, result.action(), result.reason());
        } catch (Exception e) {
            log.error("[{}] Failed to process email '{}' for account '{}': {}",
                    mode, subject, accountName, e.getMessage(), e);
        }
    }
}
