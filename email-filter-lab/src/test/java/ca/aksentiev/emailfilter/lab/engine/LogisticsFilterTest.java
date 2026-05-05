package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that LogisticsFilter returns ALL actions of the first matching rule —
 * the previous "primary action only" behavior is gone.
 */
class LogisticsFilterTest {

    private LogisticsFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LogisticsFilter(new ConditionEvaluator(), new VariableResolver());
    }

    @Test
    void returnsBothFlagAndMoveActionsFromFlyerRule() {
        // Mirrors the [FLYER]/[SALE] rule from staging-filters.yml:137-140
        LogisticsRule flyerRule = new LogisticsRule(
                anyOf(
                        startsWith("[FLYER"),
                        startsWith("[SALE")),
                List.of(
                        action(ActionType.FLAG),
                        moveToFolder("Deals")));

        FilterDefinition def = logisticsDef(List.of(flyerRule));

        FilterResult result = filter.execute(def, email("[FLYER] Best Buy weekly ad"));

        assertThat(result.actions())
                .extracting(ResolvedAction::type)
                .containsExactly(ActionType.FLAG, ActionType.MOVE_TO_FOLDER);
        assertThat(result.actions().get(1).targetFolder()).isEqualTo("Deals");
    }

    @Test
    void firstMatchingRuleWinsLaterRulesIgnored() {
        LogisticsRule first = new LogisticsRule(
                startsWith("[Job Alert]"),
                List.of(moveToFolder("Job Alerts")));
        LogisticsRule second = new LogisticsRule(
                startsWith("[Job"),
                List.of(moveToFolder("Wrong Folder")));

        FilterDefinition def = logisticsDef(List.of(first, second));

        FilterResult result = filter.execute(def, email("[Job Alert] LinkedIn — Senior Engineer"));

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).targetFolder()).isEqualTo("Job Alerts");
    }

    @Test
    void noMatchingRuleReturnsLeave() {
        LogisticsRule rule = new LogisticsRule(
                startsWith("[Job Alert]"),
                List.of(moveToFolder("Job Alerts")));

        FilterDefinition def = logisticsDef(List.of(rule));

        FilterResult result = filter.execute(def, email("Random subject"));

        assertThat(result.isLeave()).isTrue();
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void emptyRulesReturnsLeave() {
        FilterDefinition def = logisticsDef(List.of());

        FilterResult result = filter.execute(def, email("anything"));

        assertThat(result.isLeave()).isTrue();
    }

    @Test
    void resolvesEmailVariablesInActionFields() {
        LogisticsRule rule = new LogisticsRule(
                startsWith("Hello"),
                List.of(new ActionDefinition(
                        ActionType.SEND_EMAIL,
                        null,
                        "alerts@example.com",
                        "Forwarded: {email.subject}",
                        "Original from: {email.from}")));

        FilterDefinition def = logisticsDef(List.of(rule));

        FilterResult result = filter.execute(def,
                new EmailMessage("<id>", "sender@example.com", "Sender",
                        List.of("me@example.com"), "Hello world", "body", "", null, Map.of()));

        ResolvedAction sendEmail = result.actions().get(0);
        assertThat(sendEmail.emailSubject()).isEqualTo("Forwarded: Hello world");
        assertThat(sendEmail.emailBody()).isEqualTo("Original from: sender@example.com");
        assertThat(sendEmail.emailTo()).isEqualTo("alerts@example.com");
    }

    @Test
    void destructiveActionInRuleMarksResultAsDestructive() {
        LogisticsRule rule = new LogisticsRule(
                startsWith("invoice"),
                List.of(action(ActionType.FLAG), moveToFolder("Finance")));

        FilterDefinition def = logisticsDef(List.of(rule));

        FilterResult result = filter.execute(def, email("invoice 1234"));

        assertThat(result.isDestructive()).isTrue();
        assertThat(result.primaryActionType()).isEqualTo(ActionType.MOVE_TO_FOLDER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FilterDefinition logisticsDef(List<LogisticsRule> rules) {
        return new FilterDefinition(
                FilterType.LOGISTICS,
                true,
                null,
                null, null, null, null,
                null, null, null, null,
                rules);
    }

    private EmailMessage email(String subject) {
        return new EmailMessage(
                "<id>", "sender@example.com", "Sender", List.of("me@example.com"),
                subject, "body", "", null, Map.of());
    }

    private Condition startsWith(String prefix) {
        return new Condition(prefix, null, null, null, null, null, null, null, null);
    }

    private Condition anyOf(Condition... conds) {
        return new Condition(null, null, null, null, null, null, null, List.of(conds), null);
    }

    private ActionDefinition action(ActionType type) {
        return new ActionDefinition(type, null, null, null, null);
    }

    private ActionDefinition moveToFolder(String folder) {
        return new ActionDefinition(ActionType.MOVE_TO_FOLDER, folder, null, null, null);
    }
}
