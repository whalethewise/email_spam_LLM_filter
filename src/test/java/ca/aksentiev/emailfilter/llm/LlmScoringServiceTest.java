package ca.aksentiev.emailfilter.llm;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.preprocessor.BrandImpersonation;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorFindings;
import ca.aksentiev.emailfilter.preprocessor.SuspiciousUrl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmScoringServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    private LlmScoringService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new LlmScoringService(chatClientBuilder, objectMapper);
    }

    @Test
    void parsesSuccessfulJsonResponse() {
        LlmResponse result = service.parseResponse("{\"score\": 8, \"reason\": \"Phishing attempt detected\"}");

        assertThat(result.available()).isTrue();
        assertThat(result.score()).isEqualTo(8.0);
        assertThat(result.reason()).isEqualTo("Phishing attempt detected");
    }

    @Test
    void parsesJsonEmbeddedInExtraText() {
        String response = "Here is my analysis:\n{\"score\": 3, \"reason\": \"Looks legitimate\"}\nDone.";

        LlmResponse result = service.parseResponse(response);

        assertThat(result.available()).isTrue();
        assertThat(result.score()).isEqualTo(3.0);
        assertThat(result.reason()).isEqualTo("Looks legitimate");
    }

    @Test
    void malformedJsonReturnsFallbackScore() {
        LlmResponse result = service.parseResponse("This is not JSON at all");

        assertThat(result.available()).isTrue();
        assertThat(result.score()).isEqualTo(5.0);
        assertThat(result.reason()).isEqualTo("LLM response parsing failed");
    }

    @Test
    void emptyResponseReturnsFallbackScore() {
        LlmResponse result = service.parseResponse("");

        assertThat(result.available()).isTrue();
        assertThat(result.score()).isEqualTo(5.0);
    }

    @Test
    void clampsScoreToValidRange() {
        LlmResponse tooHigh = service.parseResponse("{\"score\": 15, \"reason\": \"very spam\"}");
        assertThat(tooHigh.score()).isEqualTo(10.0);

        LlmResponse tooLow = service.parseResponse("{\"score\": -3, \"reason\": \"very clean\"}");
        assertThat(tooLow.score()).isEqualTo(1.0);
    }

    @Test
    void unavailableOllamaReturnsUnavailable() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec afterSystem = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec afterUser = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(afterSystem);
        when(afterSystem.user(any(String.class))).thenReturn(afterUser);
        when(afterUser.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("Connection refused"));

        ParsedEmail email = testEmail();
        PreProcessorFindings findings = cleanFindings();

        LlmResponse result = service.score(email, findings);

        assertThat(result.available()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void promptIncludesBrandImpersonation() {
        ParsedEmail email = testEmail();
        BrandImpersonation imp =
                new BrandImpersonation("Costco", "c0stc0", "costco", List.of("costco.com", "costco.ca"));
        PreProcessorFindings findings =
                new PreProcessorFindings("Your Costco account", "Click here", List.of(imp), List.of(), false, false, 4.0);

        String prompt = service.buildUserPrompt(email, findings);

        assertThat(prompt).contains("BRAND IMPERSONATION");
        assertThat(prompt).contains("c0stc0");
        assertThat(prompt).contains("costco");
        assertThat(prompt).contains("costco.com");
    }

    @Test
    void promptIncludesSuspiciousUrls() {
        ParsedEmail email = testEmail();
        SuspiciousUrl url = new SuspiciousUrl("http://1.2.3.4/login", "IP address URL");
        PreProcessorFindings findings =
                new PreProcessorFindings("Subject", "Body", List.of(), List.of(url), false, false, 2.0);

        String prompt = service.buildUserPrompt(email, findings);

        assertThat(prompt).contains("SUSPICIOUS URL");
        assertThat(prompt).contains("http://1.2.3.4/login");
        assertThat(prompt).contains("IP address URL");
    }

    @Test
    void promptIncludesUnicodeSpoofingAndZeroWidth() {
        ParsedEmail email = testEmail();
        PreProcessorFindings findings =
                new PreProcessorFindings("Subject", "Body", List.of(), List.of(), true, true, 5.0);

        String prompt = service.buildUserPrompt(email, findings);

        assertThat(prompt).contains("UNICODE SPOOFING");
        assertThat(prompt).contains("ZERO-WIDTH CHARACTERS");
    }

    @Test
    void promptTruncatesLongBody() {
        ParsedEmail email = testEmail();
        String longBody = "x".repeat(3000);
        PreProcessorFindings findings =
                new PreProcessorFindings("Subject", longBody, List.of(), List.of(), false, false, 1.0);

        String prompt = service.buildUserPrompt(email, findings);

        assertThat(prompt).contains("... [truncated]");
        assertThat(prompt).doesNotContain("x".repeat(3000));
    }

    private ParsedEmail testEmail() {
        return new ParsedEmail(
                "<test@example.com>",
                "Test Subject",
                "sender@example.com",
                "Test Sender",
                List.of("me@example.com"),
                "Test body content",
                Map.of(),
                Instant.now());
    }

    private PreProcessorFindings cleanFindings() {
        return new PreProcessorFindings("Test Subject", "Test body content", List.of(), List.of(), false, false, 1.0);
    }
}
