package ca.aksentiev.emailfilter.lab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the source-domains / source-subjects gate logic in ExtractionFilter.
 * Spec FILTER-SPEC.md:175-195, 322-326, 539: gates are OR'd, domain checked first,
 * neither configured → fall back to sender as {source}, neither matches → LEAVE.
 */
@ExtendWith(MockitoExtension.class)
class ExtractionFilterGateTest {

    @Mock private ChatClient.Builder builder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private ExtractionFilter filter;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(chatClient);
        // Lenient — not every test calls the LLM (LEAVE-path tests don't).
        lenient().when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
        lenient().when(callResponseSpec.content()).thenReturn("extracted content");

        filter = new ExtractionFilter(new WhitelistMatcher(), new VariableResolver(), builder);
    }

    @Test
    void domainGateMatch_runsLlmAndPopulatesSourceFromDomainMap() {
        FilterDefinition def = extractionDef(
                Map.of("linkedin.com", "LinkedIn"),
                null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        FilterResult result = filter.execute(def, email("user@linkedin.com", "Anything"));

        verify(chatClient).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Source: LinkedIn")
                .contains("Domain: linkedin.com");
        assertThat(result.llmResponse()).isEqualTo("extracted content");
    }

    @Test
    void subjectGateMatchesWhenNoDomainMatch() {
        // Mirrors the spec example: jobs2web.com sender, but subject identifies the org
        FilterDefinition def = extractionDef(
                Map.of("linkedin.com", "LinkedIn"),
                Map.of("New jobs posted by Canadian Blood Services", "Canadian Blood Services"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        FilterResult result = filter.execute(def,
                email("noreply@jobs2web.com", "New jobs posted by Canadian Blood Services - Ottawa"));

        verify(chatClient).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Source: Canadian Blood Services")
                .contains("Domain: ");  // empty when matched via subject
        assertThat(result.llmResponse()).isEqualTo("extracted content");
    }

    @Test
    void subjectGateIsCaseInsensitive() {
        FilterDefinition def = extractionDef(
                null,
                Map.of("New Jobs Posted By", "Recruiter"));

        FilterResult result = filter.execute(def,
                email("anyone@elsewhere.com", "new jobs posted by acme corp"));

        verify(chatClient).prompt(anyString());
        assertThat(result.actions()).isNotNull();  // didn't LEAVE
    }

    @Test
    void domainGateTakesPrecedenceOverSubjectGate() {
        // Both gates could match — domain check runs first per spec section 322a/b
        FilterDefinition def = extractionDef(
                Map.of("linkedin.com", "LinkedIn"),
                Map.of("Fancy subject", "Subject Source"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        filter.execute(def, email("user@linkedin.com", "Fancy subject indeed"));

        verify(chatClient).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Source: LinkedIn")
                .doesNotContain("Source: Subject Source");
    }

    @Test
    void neitherGateMatchesWithBothConfigured_returnsLeaveWithoutLlmCall() {
        FilterDefinition def = extractionDef(
                Map.of("linkedin.com", "LinkedIn"),
                Map.of("New jobs posted by", "Recruiter"));

        FilterResult result = filter.execute(def,
                email("random@example.com", "Random subject"));

        assertThat(result.isLeave()).isTrue();
        assertThat(result.reason()).isEqualTo("no source gate matched");
        verify(chatClient, never()).prompt(anyString());
    }

    @Test
    void onlySubjectGateConfigured_andNoMatch_returnsLeave() {
        FilterDefinition def = extractionDef(
                null,
                Map.of("Specific prefix", "Source"));

        FilterResult result = filter.execute(def,
                email("user@example.com", "Different prefix here"));

        assertThat(result.isLeave()).isTrue();
        verify(chatClient, never()).prompt(anyString());
    }

    @Test
    void neitherGateConfigured_fallsBackToSenderAsSource() {
        // Spec line 539: "Falls back to sender address if no gate is configured."
        FilterDefinition def = extractionDef(null, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        filter.execute(def, email("alice@example.com", "anything"));

        verify(chatClient).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Source: alice@example.com")
                .contains("Domain: example.com");
    }

    @Test
    void emptyGatesAreTreatedAsAbsent_fallsBackToSender() {
        // domain map and subject map both present but empty — same as null
        FilterDefinition def = extractionDef(Map.of(), Map.of());

        FilterResult result = filter.execute(def, email("bob@example.org", "anything"));

        verify(chatClient).prompt(anyString());
        assertThat(result.actions()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FilterDefinition extractionDef(Map<String, String> sourceDomains,
                                            Map<String, String> sourceSubjects) {
        // Prompt embeds a few markers we can grep in the captor
        String prompt = "Source: {source}\nDomain: {source.domain}\nFrom: {email.from}\n{email.body}";
        ExtractionActions actions = new ExtractionActions(
                List.of(new ActionDefinition(ActionType.MOVE_TO_FOLDER, "Processed", null, null, null)),
                List.of());

        return new FilterDefinition(
                FilterType.EXTRACTION,
                true,
                "test-model",
                null, null, null, null,
                sourceDomains,
                sourceSubjects,
                null,
                prompt,
                actions,
                null);
    }

    private EmailMessage email(String from, String subject) {
        Map<String, String> headers = new LinkedHashMap<>();
        return new EmailMessage(
                "<id>", from, "", List.of("me@example.com"),
                subject, "body", "", null, headers);
    }
}
