package ca.aksentiev.emailfilter.scoring;

/**
 * Score contribution from a single scoring layer.
 *
 * @param name      layer identifier ("preprocessor", "spamassassin", "llm")
 * @param score     the layer's spam score (1-10)
 * @param weight    configured weight for this layer (before redistribution)
 * @param available whether this layer produced a result
 */
public record LayerScore(String name, double score, double weight, boolean available) {}
