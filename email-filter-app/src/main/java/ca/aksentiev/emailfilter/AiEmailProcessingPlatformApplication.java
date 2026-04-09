package ca.aksentiev.emailfilter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("ca.aksentiev.emailfilter.config")
public class AiEmailProcessingPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiEmailProcessingPlatformApplication.class, args);
    }

}
