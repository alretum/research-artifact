package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.util.List;
import java.util.Set;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "de.tum.cit.aet.artemis.hyperion.mcq")
@ConfigurationPropertiesScan("de.tum.cit.aet.artemis.hyperion.mcq")
public class McqPipelineApplication {

    /**
     * Arguments that mean "do this one thing and exit" rather than "serve the interface".
     */
    private static final Set<String> COMMAND_ARGUMENTS = Set.of("count", "resume", "report", "sweep", "cost", "retrieval-only", "export");

    public static void main(String[] args) {
        boolean command = List.of(args).stream().anyMatch(argument -> COMMAND_ARGUMENTS.stream().anyMatch(name -> argument.startsWith("--" + name)));
        new SpringApplicationBuilder(McqPipelineApplication.class).web(command ? WebApplicationType.NONE : WebApplicationType.SERVLET).run(args);
    }
}
