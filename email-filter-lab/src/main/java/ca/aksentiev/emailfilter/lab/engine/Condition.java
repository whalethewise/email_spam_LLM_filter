package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

public record Condition(
        String subjectStartsWith,
        String subjectEndsWith,
        String subjectContains,
        String fromDomain,
        String fromAddress,
        String fromAddressContains,
        List<Condition> allOf,
        List<Condition> anyOf,
        Condition not) {}
