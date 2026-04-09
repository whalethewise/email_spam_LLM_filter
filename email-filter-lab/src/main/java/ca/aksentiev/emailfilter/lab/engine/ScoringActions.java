package ca.aksentiev.emailfilter.lab.engine;

public record ScoringActions(
        String safe,
        String review,
        String spam) {}
