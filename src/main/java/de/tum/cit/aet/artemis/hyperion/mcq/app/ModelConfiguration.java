package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.tum.cit.aet.artemis.hyperion.mcq.llm.ModelRegistry;

/**
 * Builds the {@link ModelRegistry} from configuration.
 * <p>
 * Wiring lives here rather than in {@code llm} so the registry itself needs no knowledge of how backends are
 * configured.
 */
@Configuration
public class ModelConfiguration {

    /**
     * @param properties pipeline configuration supplying the {@code mcq.models} map
     * @return a registry over every configured backend
     */
    @Bean
    public ModelRegistry modelRegistry(PipelineProperties properties) {
        Map<String, ModelRegistry.Backend> backends = new LinkedHashMap<>();
        properties.models().forEach((key, backend) -> backends.put(key, new ModelRegistry.Backend(backend.baseUrl(), backend.apiKey(), backend.model())));
        return new ModelRegistry(backends);
    }
}
