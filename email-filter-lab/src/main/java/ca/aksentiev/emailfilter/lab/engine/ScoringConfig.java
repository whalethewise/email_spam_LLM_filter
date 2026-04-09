package ca.aksentiev.emailfilter.lab.engine;

public record ScoringConfig(
        ScoringWeights weights,
        ScoringThresholds thresholds,
        ScoringActions actions) {}
