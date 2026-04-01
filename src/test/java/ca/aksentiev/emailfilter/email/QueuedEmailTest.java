package ca.aksentiev.emailfilter.email;

import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueuedEmailTest {

    private final EmailMessage message = new EmailMessage(
            "<id@test>", "from@test.com", "Sender", List.of("to@test.com"),
            "Subject", "body", "", null, Map.of());

    private final AccountProperties.Account account = new AccountProperties.Account(
            "test", "imap.test.com", "user", "pass",
            List.of("spam-filter"), new AccountProperties.Folders("INBOX", "Review", "Junk"));

    @Test
    void twoArgConstructorDefaultsForceDryRunToFalse() {
        QueuedEmail item = new QueuedEmail(message, account);
        assertThat(item.forceDryRun()).isFalse();
    }

    @Test
    void threeArgConstructorPreservesForceDryRun() {
        QueuedEmail item = new QueuedEmail(message, account, true);
        assertThat(item.forceDryRun()).isTrue();
    }
}
