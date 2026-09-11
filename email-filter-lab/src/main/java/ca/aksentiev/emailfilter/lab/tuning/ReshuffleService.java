package ca.aksentiev.emailfilter.lab.tuning;

import java.util.ArrayList;
import java.util.List;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.filter.spam.Whitelist;
import ca.aksentiev.emailfilter.lab.config.LabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Re-scores emails already sitting in the review folder — each carrying a
 * {@code [PP:x/SA:y/LLM:z=combined]} tag from the main app — against a fresh
 * LLM prompt, recombines with the existing PP/SA layers, and decides where
 * each one should land: back to the inbox, kept in review with an updated
 * tag, or moved to junk.
 */
@Component
public class ReshuffleService {

    private static final Logger log = LoggerFactory.getLogger(ReshuffleService.class);

    private final TuningLlmScorer scorer;
    private final Whitelist whitelist;
    private final LabProperties properties;

    public ReshuffleService(TuningLlmScorer scorer, Whitelist tuningWhitelist, LabProperties properties) {
        this.scorer = scorer;
        this.whitelist = tuningWhitelist;
        this.properties = properties;
    }

    public List<ReshufflePlan> buildPlan(List<EmailMessage> emails, String promptTemplate) {
        LabProperties.Tuning.Reshuffle cfg = properties.getTuning().reshuffle();
        String reviewFolder = properties.getImap().folder();
        List<ReshufflePlan> results = new ArrayList<>(emails.size());

        for (int i = 0; i < emails.size(); i++) {
            EmailMessage email = emails.get(i);
            SubjectTagParser.ParsedTag tag = SubjectTagParser.parse(email.subject());
            String cleanSubject = tag != null ? tag.cleanSubject() : email.subject();
            int pp = tag != null ? tag.pp() : 0;
            int sa = tag != null ? tag.sa() : 0;

            if (whitelist.isWhitelisted(email.from())) {
                log.info("[{}/{}] WHITELISTED: \"{}\"", i + 1, emails.size(), truncate(cleanSubject, 50));
                results.add(new ReshufflePlan(email, email.subject(), 0, 0, 0, 0,
                        cleanSubject, cfg.inbox(), "WHITELISTED", "Sender whitelisted"));
                continue;
            }

            log.info("[{}/{}] Scoring: \"{}\"", i + 1, emails.size(), truncate(cleanSubject, 60));
            TuningScoreResult scored = scorer.score(withSubject(email, cleanSubject), promptTemplate);
            int llm = scored.score();
            log.info("         Score: {} — {}", llm, truncate(scored.reason(), 70));

            int combined = computeCombined(pp, sa, llm, cfg);

            String action;
            String destination;
            String newSubject;
            if (llm <= cfg.safeMax()) {
                action = "MOVE_TO_INBOX";
                destination = cfg.inbox();
                newSubject = cleanSubject;
            } else if (llm <= cfg.reviewMax()) {
                action = "KEEP_IN_REVIEW";
                destination = reviewFolder;
                newSubject = SubjectTagParser.buildTag(pp, sa, llm, combined) + " " + cleanSubject;
            } else {
                action = "MOVE_TO_JUNK";
                destination = cfg.junk();
                newSubject = SubjectTagParser.buildTag(pp, sa, llm, combined) + " " + cleanSubject;
            }

            results.add(new ReshufflePlan(email, email.subject(), pp, sa, llm, combined,
                    newSubject, destination, action, scored.reason()));
        }

        return results;
    }

    private int computeCombined(int pp, int sa, int llm, LabProperties.Tuning.Reshuffle cfg) {
        double raw = (pp * cfg.weightPreprocessor())
                + (sa * cfg.weightSpamassassin())
                + (llm * cfg.weightLlm());
        return (int) Math.round(raw);
    }

    private EmailMessage withSubject(EmailMessage email, String subject) {
        return new EmailMessage(email.messageId(), email.from(), email.fromName(), email.to(),
                subject, email.bodyText(), email.bodyHtml(), email.rawMessage(), email.headers());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
