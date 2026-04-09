package ca.aksentiev.emailfilter.lab.engine;

public record SpamAssassinConfig(
        boolean enabled,
        String host,
        int port,
        double skipLlmAboveScore) {}
