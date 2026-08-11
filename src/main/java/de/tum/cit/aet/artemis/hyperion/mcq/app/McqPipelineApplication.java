package de.tum.cit.aet.artemis.hyperion.mcq.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "de.tum.cit.aet.artemis.hyperion.mcq")
@ConfigurationPropertiesScan("de.tum.cit.aet.artemis.hyperion.mcq")
public class McqPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(McqPipelineApplication.class, args);
    }
}
