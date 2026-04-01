package ca.aksentiev.emailfilter.filter;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterResultTest {

    @Test
    void processedFactoryCreatesCorrectResult() {
        FilterResult result = FilterResult.processed(7.5, "move-to-junk", "Phishing detected");

        assertThat(result.status()).isEqualTo(FilterResult.Status.PROCESSED);
        assertThat(result.score()).isEqualTo(7.5);
        assertThat(result.action()).isEqualTo("move-to-junk");
        assertThat(result.reason()).isEqualTo("Phishing detected");
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void processedWithMetadata() {
        Map<String, Object> metadata = Map.of("llmReason", "Brand impersonation", "saRules", "BAYES_99");
        FilterResult result = FilterResult.processed(8.0, "move-to-review", "Spam detected", metadata);

        assertThat(result.status()).isEqualTo(FilterResult.Status.PROCESSED);
        assertThat(result.metadata()).containsEntry("llmReason", "Brand impersonation");
        assertThat(result.metadata()).containsEntry("saRules", "BAYES_99");
    }

    @Test
    void skippedFactoryCreatesCorrectResult() {
        FilterResult result = FilterResult.skipped("Sender whitelisted");

        assertThat(result.status()).isEqualTo(FilterResult.Status.SKIPPED);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.action()).isEqualTo("leave");
        assertThat(result.reason()).isEqualTo("Sender whitelisted");
    }

    @Test
    void skippedContinuesChain() {
        FilterResult result = FilterResult.skipped("Not applicable");

        assertThat(result.shouldContinueChain()).isTrue();
    }

    @Test
    void leaveActionContinuesChain() {
        FilterResult result = FilterResult.processed(2.0, "leave", "Clean email");

        assertThat(result.shouldContinueChain()).isTrue();
    }

    @Test
    void nonLeaveActionStopsChain() {
        FilterResult result = FilterResult.processed(8.0, "move-to-junk", "Spam");

        assertThat(result.shouldContinueChain()).isFalse();
    }

    @Test
    void moveToReviewStopsChain() {
        FilterResult result = FilterResult.processed(5.0, "move-to-review", "Suspicious");

        assertThat(result.shouldContinueChain()).isFalse();
    }

    @Test
    void deleteStopsChain() {
        FilterResult result = FilterResult.processed(9.0, "delete", "Known spam");

        assertThat(result.shouldContinueChain()).isFalse();
    }
}
