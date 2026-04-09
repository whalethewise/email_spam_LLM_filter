package ca.aksentiev.emailfilter.lab.engine;

public record ActionDefinition(
        ActionType type,
        String folder,
        String to,
        String subject,
        String body) {}
