package ca.aksentiev.emailfilter.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "ca.aksentiev.emailfilter")
@ConfigurationPropertiesScan({"ca.aksentiev.emailfilter.lab.config", "ca.aksentiev.emailfilter.config"})
public class EmailFilterLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmailFilterLabApplication.class, args);
    }
}
