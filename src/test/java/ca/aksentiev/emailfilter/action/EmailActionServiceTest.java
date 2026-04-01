package ca.aksentiev.emailfilter.action;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.DryRunProperties;
import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.scoring.LayerScore;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import jakarta.mail.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailActionServiceTest {

    @Mock
    private AuditService auditService;

    @Mock
    private DryRunReportService dryRunReportService;

    private SubjectTagger subjectTagger;
    private SpamFilterProperties spamFilterProperties;

    @BeforeEach
    void setUp() {
        subjectTagger = new SubjectTagger();
        spamFilterProperties = new SpamFilterProperties(
                true,
                "mistral",
                8.0,
                "classpath:brands.json",
                "classpath:char_substitutions.json",
                new SpamFilterProperties.Weights(0.20, 0.35, 0.45),
                new SpamFilterProperties.Thresholds(3, 6),
                new SpamFilterProperties.Actions("none", "move-to-review", "move-to-junk"));
    }

    @Test
    void dryRunRecordsDecisionWithoutExecuting() throws Exception {
        DryRunProperties dryRunProps = new DryRunProperties(true, "admin@example.com", null, "0 7 * * *");
        EmailActionService service =
                new EmailActionService(spamFilterProperties, dryRunProps, subjectTagger, auditService, dryRunReportService);

        ParsedEmail email = testEmail("Suspicious offer");
        ScoreResult score = scoreResult(8.0, ScoreCategory.SPAM);
        Message message = mock(Message.class);
        AccountProperties.Account account = testAccount();

        service.execute(email, score, message, account);

        // Dry-run should record the decision
        verify(dryRunReportService).recordDecision(eq(email), eq(score), eq("move-to-junk"), anyString());
        // Audit should still be called (with dryRun=true)
        verify(auditService).record(email, score, "move-to-junk", true);
        // Message should NOT be modified in dry-run
        verify(message, never()).setFlag(any(), eq(true));
        verify(message, never()).setSubject(anyString());
    }

    @Test
    void resolvesSafeAction() {
        DryRunProperties dryRunProps = new DryRunProperties(false, null, null, null);
        EmailActionService service =
                new EmailActionService(spamFilterProperties, dryRunProps, subjectTagger, auditService, dryRunReportService);

        assertThat(service.resolveAction(ScoreCategory.SAFE)).isEqualTo("none");
    }

    @Test
    void resolvesReviewAction() {
        DryRunProperties dryRunProps = new DryRunProperties(false, null, null, null);
        EmailActionService service =
                new EmailActionService(spamFilterProperties, dryRunProps, subjectTagger, auditService, dryRunReportService);

        assertThat(service.resolveAction(ScoreCategory.REVIEW)).isEqualTo("move-to-review");
    }

    @Test
    void resolvesSpamAction() {
        DryRunProperties dryRunProps = new DryRunProperties(false, null, null, null);
        EmailActionService service =
                new EmailActionService(spamFilterProperties, dryRunProps, subjectTagger, auditService, dryRunReportService);

        assertThat(service.resolveAction(ScoreCategory.SPAM)).isEqualTo("move-to-junk");
    }

    @Test
    void auditCalledForLiveMode() throws Exception {
        DryRunProperties dryRunProps = new DryRunProperties(false, null, null, null);
        EmailActionService service =
                new EmailActionService(spamFilterProperties, dryRunProps, subjectTagger, auditService, dryRunReportService);

        ParsedEmail email = testEmail("Clean email");
        ScoreResult score = scoreResult(2.0, ScoreCategory.SAFE);
        Message message = mock(Message.class);
        AccountProperties.Account account = testAccount();

        service.execute(email, score, message, account);

        verify(auditService).record(email, score, "none", false);
        verify(dryRunReportService, never()).recordDecision(any(), any(), any(), any());
    }

    private ParsedEmail testEmail(String subject) {
        return new ParsedEmail(
                "<test@example.com>", subject, "sender@example.com", "Sender Name", List.of("me@example.com"),
                "Test body", Map.of(), Instant.now());
    }

    private ScoreResult scoreResult(double finalScore, ScoreCategory category) {
        return new ScoreResult(
                finalScore,
                category,
                List.of(
                        new LayerScore("preprocessor", 3.0, 0.20, true),
                        new LayerScore("spamassassin", 8.0, 0.35, true),
                        new LayerScore("llm", 9.0, 0.45, true)),
                "[PP:3/SA:8/LLM:9=" + Math.round(finalScore) + "]",
                "Test reason");
    }

    private AccountProperties.Account testAccount() {
        return new AccountProperties.Account(
                "personal",
                "imap.example.com",
                "user@example.com",
                "password",
                List.of("spam-filter"),
                new AccountProperties.Folders("INBOX", "LLM-Spam-Review", "Junk"));
    }
}
