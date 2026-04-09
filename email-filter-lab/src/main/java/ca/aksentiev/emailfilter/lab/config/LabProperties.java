package ca.aksentiev.emailfilter.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "lab")
public class LabProperties {

    private final Imap imap;
    private final Ollama ollama;
    private final Smtp smtp;
    private final Run run;

    public LabProperties(Imap imap, Ollama ollama, Smtp smtp, Run run) {
        this.imap = imap;
        this.ollama = ollama;
        this.smtp = smtp != null ? smtp : new Smtp("", "AI Email Filter Lab");
        this.run = run != null ? run : new Run("spam-filter", 0, true, null);
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

    public record Run(
            @DefaultValue("spam-filter") String filter,
            @DefaultValue("0") int limit,
            @DefaultValue("true") boolean dryRun,
            String reportFile) {}
}
