package ca.aksentiev.emailfilter.lab.engine;

public record ScoringThresholds(
        int safeMax,
        int reviewMax) {}
