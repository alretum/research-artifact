package de.tum.cit.aet.artemis.hyperion.mcq.store;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;

/**
 * Provides the run store as a single long-lived instance.
 * <p>
 * The web interface needs the store open for the lifetime of the application, and a single connection is
 * sufficient because model calls, not database access, are the bottleneck.
 */
@Configuration
public class RunStoreConfiguration {

    /**
     * @param properties pipeline configuration supplying the database path
     * @return the store, closed by the container on shutdown
     */
    @Bean(destroyMethod = "close")
    public RunStore runStore(PipelineProperties properties) {
        return new RunStore(Path.of(properties.batch().databasePath()));
    }
}
