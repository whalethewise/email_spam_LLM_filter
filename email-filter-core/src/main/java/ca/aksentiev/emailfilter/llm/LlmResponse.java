package ca.aksentiev.emailfilter.llm;

/**
 * Immutable LLM response carrying the spam score,
 * reasoning, and availability status.
 *
 * @param score     spam score from 1 (clean) to 10 (definite spam)
 * @param reason    LLM's explanation for the score
 * @param available false if Ollama was unreachable
 */
public record LlmResponse(double score, String reason, boolean available) {

    /** Returns an unavailable result with zeroed score. */
    public static LlmResponse unavailable() {
        return new LlmResponse(0.0, "LLM unavailable", false);
    }
}
