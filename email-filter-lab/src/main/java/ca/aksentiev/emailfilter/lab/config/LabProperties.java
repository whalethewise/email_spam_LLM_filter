package ca.aksentiev.emailfilter.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "lab")
public class LabProperties {

    private final Imap imap;
    private final Ollama ollama;
    private final Smtp smtp;
    private final Run run;
    private final Tuning tuning;

    public LabProperties(Imap imap, Ollama ollama, Smtp smtp, Run run, Tuning tuning) {
        this.imap = imap;
        this.ollama = ollama;
        this.smtp = smtp != null ? smtp : new Smtp("", "AI Email Filter Lab");
        this.run = run != null ? run : new Run("filter", "spam-filter", 0, true, null);
        this.tuning = tuning != null ? tuning : new Tuning("prompt.txt", null);
    }

    public Imap getImap() {
        return imap;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public Smtp getSmtp() {
        return smtp;
    }

    public Run getRun() {
        return run;
    }

    public Tuning getTuning() {
        return tuning;
    }

    public record Imap(
            String host,
            @DefaultValue("993") int port,
            @DefaultValue("true") boolean ssl,
            String username,
            String password,
            @DefaultValue("INBOX.LLM-Spam_Review") String folder) {}

    public record Ollama(
            @DefaultValue("http://192.168.10.158:11434") String baseUrl,
            @DefaultValue("qwen3.5:9b") String model,
            @DefaultValue("120") int timeoutSeconds) {}

    public record Smtp(
            @DefaultValue("") String fromAddress,
            @DefaultValue("AI Email Filter Lab") String fromName) {}

    /**
     * @param mode "filter" (default — run a staging-filters.yml filter chain),
     *             "review" (score a folder against a prompt file, report only),
     *             or "reshuffle" (re-score an already-tagged review folder and
     *             build a move plan)
     */
    public record Run(
            @DefaultValue("filter") String mode,
            @DefaultValue("spam-filter") String filter,
            @DefaultValue("0") int limit,
            @DefaultValue("true") boolean dryRun,
            String reportFile) {}

    /**
     * Settings for the "review" and "reshuffle" prompt-tuning modes.
     * The whitelist itself lives in {@code tuning-whitelist.yml} (see
     * {@link TuningWhitelistConfig}), not here, so it can be tuned and
     * reused independently of application.yml.
     */
    public record Tuning(
            @DefaultValue("prompt.txt") String promptFile,
            Reshuffle reshuffle) {

        public Tuning {
            reshuffle = reshuffle != null ? reshuffle : new Reshuffle(
                    "INBOX", "INBOX.Junk", 3, 6, 0.20, 0.35, 0.45);
        }

        public record Reshuffle(
                @DefaultValue("INBOX") String inbox,
                @DefaultValue("INBOX.Junk") String junk,
                @DefaultValue("3") int safeMax,
                @DefaultValue("6") int reviewMax,
                @DefaultValue("0.20") double weightPreprocessor,
                @DefaultValue("0.35") double weightSpamassassin,
                @DefaultValue("0.45") double weightLlm) {}
    }
}
