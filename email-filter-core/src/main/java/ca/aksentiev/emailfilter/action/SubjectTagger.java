package ca.aksentiev.emailfilter.action;

import java.util.regex.Pattern;

import ca.aksentiev.emailfilter.scoring.ScoreResult;
import org.springframework.stereotype.Component;

/**
 * Rewrites email subjects by prepending a score tag.
 * Replaces existing tags if the subject already starts with one.
 */
@Component
public class SubjectTagger {

    private static final Pattern EXISTING_TAG_PATTERN = Pattern.compile("^\\[.*?]\\s*");

    /**
     * Returns the subject with the score tag prepended.
     * If the subject already has a tag (starts with {@code [}), it is replaced.
     *
     * @param originalSubject the original email subject (may be null or empty)
     * @param scoreResult     the scoring result containing the formatted tag
     * @return tagged subject
     */
    public String tag(String originalSubject, ScoreResult scoreResult) {
        String subject = normalizeSubject(originalSubject);
        String stripped = EXISTING_TAG_PATTERN.matcher(subject).replaceFirst("");
        if (stripped.isEmpty()) {
            stripped = "(no subject)";
        }
        return scoreResult.subjectTag() + " " + stripped;
    }

    private String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "(no subject)";
        }
        return subject.trim();
    }
}
