package ca.aksentiev.emailfilter.lab.tuning;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Prints a report to the console and, if configured, writes a timestamped copy to disk. */
final class TuningReportWriter {

    private static final Logger log = LoggerFactory.getLogger(TuningReportWriter.class);

    private TuningReportWriter() {}

    static void print(String report) {
        System.out.println(report);
    }

    static void writeToFile(String report, String filePath, String suffix) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        String timestamped = injectSuffixAndTimestamp(filePath, suffix);
        try {
            Path resolved = Path.of(timestamped).toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            if (!resolved.startsWith(cwd)) {
                log.error("Report path resolves outside working directory: {}", resolved);
                return;
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter(resolved.toFile()))) {
                writer.print(report);
                log.info("Report written to: {}", resolved);
            }
        } catch (IOException e) {
            log.error("Failed to write report to '{}': {}", timestamped, e.getMessage());
        }
    }

    private static String injectSuffixAndTimestamp(String filename, String suffix) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HHmm"));
        String tag = suffix == null || suffix.isBlank() ? stamp : suffix + "_" + stamp;
        int dot = filename.lastIndexOf('.');
        if (dot == -1) {
            return filename + "_" + tag;
        }
        return filename.substring(0, dot) + "_" + tag + filename.substring(dot);
    }
}
