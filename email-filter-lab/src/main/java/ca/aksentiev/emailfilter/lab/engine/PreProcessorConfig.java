package ca.aksentiev.emailfilter.lab.engine;

public record PreProcessorConfig(
        boolean enabled,
        String brandsFile,
        String charSubstitutionsFile) {}
