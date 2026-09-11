package ca.aksentiev.emailfilter.lab.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads a YAML config file for the lab module: tries the working directory
 * first, then {@code email-filter-lab/<file>} (for running from the repo
 * root), then falls back to the classpath.
 */
final class LabYamlLoader {

    private static final Logger log = LoggerFactory.getLogger(LabYamlLoader.class);

    private LabYamlLoader() {}

    static Map<String, Object> load(String filename) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        File file = new File(filename);
        if (!file.exists()) {
            file = new File("email-filter-lab/" + filename);
        }
        if (file.exists()) {
            try {
                Path realPath = file.toPath().toRealPath();
                Path cwd = Path.of("").toAbsolutePath().toRealPath();
                if (!realPath.startsWith(cwd)) {
                    log.error("{} resolves outside working directory: {}", filename, realPath);
                    return null;
                }
                log.info("Loading {} from: {}", filename, realPath);
                try (FileInputStream fis = new FileInputStream(realPath.toFile())) {
                    return yaml.load(fis);
                }
            } catch (IOException e) {
                log.error("Failed to load {} from disk: {}", filename, e.getMessage());
            }
        }

        log.info("Loading {} from classpath", filename);
        try (InputStream is = LabYamlLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (is != null) {
                return yaml.load(is);
            }
        } catch (Exception e) {
            log.error("Failed to load {} from classpath: {}", filename, e.getMessage());
        }

        log.error("{} not found on disk or classpath", filename);
        return null;
    }
}
