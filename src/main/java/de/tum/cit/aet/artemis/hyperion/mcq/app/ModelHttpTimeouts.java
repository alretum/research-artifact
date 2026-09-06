package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.time.Duration;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.core.Timeout;

/**
 * Timeouts for the HTTP client behind every chat call.
 * <p>
 * The OpenAI SDK client the chat auto-configuration builds is customised here; the
 * {@code spring.ai.openai.timeout} property does not reach it. Connecting is bounded tightly, so a dead
 * route fails within seconds and the call is retried, while reading is bounded loosely: a twenty-question
 * generation on a busy GPU legitimately takes around ten minutes before the first response byte arrives.
 */
@Configuration
public class ModelHttpTimeouts {

    /** Ceiling on establishing a connection; a black-holed route fails here instead of hanging. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Ceiling on waiting for the response; must exceed the slowest legitimate generation call. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(20);

    /**
     * Bounds the connect and response phases of every chat call.
     *
     * @return the customizer the OpenAI client auto-configuration applies
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer modelCallTimeouts() {
        return builder -> builder
                .timeout(Timeout.builder().connect(CONNECT_TIMEOUT).read(RESPONSE_TIMEOUT).write(Duration.ofMinutes(1)).request(RESPONSE_TIMEOUT).build());
    }
}
