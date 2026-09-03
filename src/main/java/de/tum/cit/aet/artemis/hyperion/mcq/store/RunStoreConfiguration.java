package de.tum.cit.aet.artemis.hyperion.mcq.store;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;

/**
 * Provides the run store as a single application-scoped instance.
 * <p>
 * One connection is shared by every caller and closed by the container on shutdown. {@link RunStore}
 * synchronises per instance, so nothing else may open the same database file concurrently.
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
