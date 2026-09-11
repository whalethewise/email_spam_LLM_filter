package ca.aksentiev.emailfilter.lab.tuning;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and strips the {@code [PP:x/SA:y/LLM:z=combined]} subject tag
 * written by {@code SubjectTagger} in the main app (see FILTER-SPEC.md).
 */
public final class SubjectTagParser {

    private static final Pattern TAG_PATTERN =
            Pattern.compile("\\s*\\[PP:(\\d+)/SA:(\\d+|-)/LLM:(\\d+)=(\\d+)]\\s*");

    private SubjectTagParser() {}

    public record ParsedTag(int pp, int sa, int llm, int combined, String cleanSubject) {}

    public static ParsedTag parse(String subject) {
        if (subject == null) {
            return null;
        }
        Matcher m = TAG_PATTERN.matcher(subject);
        if (!m.find()) {
            return null;
        }

        int pp = Integer.parseInt(m.group(1));
        String saStr = m.group(2);
        int sa = "-".equals(saStr) ? 0 : Integer.parseInt(saStr);
        int llm = Integer.parseInt(m.group(3));
        int combined = Integer.parseInt(m.group(4));
        String clean = (subject.substring(0, m.start()) + subject.substring(m.end())).trim();

        return new ParsedTag(pp, sa, llm, combined, clean);
    }

    public static String buildTag(int pp, int sa, int llm, int combined) {
        return String.format("[PP:%d/SA:%d/LLM:%d=%d]", pp, sa, llm, combined);
    }

    public static String stripTag(String subject) {
        if (subject == null) {
            return null;
        }
        return TAG_PATTERN.matcher(subject).replaceAll("").trim();
    }
}
