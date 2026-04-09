package ca.aksentiev.emailfilter.lab.engine;

public record ScoringWeights(
        double preprocessor,
        double spamassassin,
        double llm) {}
