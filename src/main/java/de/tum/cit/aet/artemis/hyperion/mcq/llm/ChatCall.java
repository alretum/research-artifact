package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;

/**
 * Issues a single chat completion, selecting the model per call and owning the retry loop.
 * <p>
 * The model name given here is the name sent to the provider, so a recorded {@link CallRecord} cannot
 * disagree with the model that produced the response. Retries are performed here rather than inside the
 * client so that the reported attempt count is accurate; {@code spring.ai.retry.max-attempts} must
 * therefore be 1.
 */
public final class ChatCall {

    private static final Logger log = LoggerFactory.getLogger(ChatCall.class);

    private static final long INITIAL_BACKOFF_MS = 1_000;

    private static final long MAX_BACKOFF_MS = 30_000;

    private ChatCall() {
    }

    /**
     * Result of a call, successful or not.
     *
     * @param response the provider response, or {@code null} when every attempt failed
     * @param record   telemetry for the call, always present
     */
    public record Outcome(ChatResponse response, CallRecord record) {

        public boolean succeeded() {
            return response != null;
        }

        /**
         * @return the assistant message text, or {@code null} when absent
         */
        public String text() {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        }
    }

    /**
     * Issue a completion, retrying transient failures with exponential backoff.
     *
     * @param stage       telemetry label for the pipeline stage, for example {@code "generation"}
     * @param model       provider model name, sent with the request and recorded verbatim
     * @param temperature sampling temperature
     * @param maxAttempts total attempts including the first; must be at least 1
     * @param client      the chat client to issue through
     * @param system      system prompt
     * @param user        user prompt
     * @return the outcome, carrying telemetry whether or not the call succeeded
     * @throws IllegalArgumentException if {@code maxAttempts} is below 1
     */
    public static Outcome execute(String stage, String model, double temperature, int maxAttempts, ChatClient client, String system, String user) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }

        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResponse response = client.prompt().system(system).user(user).options(OpenAiChatOptions.builder().model(model).temperature(temperature)).call().chatResponse();
                return new Outcome(response, record(requestId, stage, model, start, attempt - 1, response, null));
            }
            catch (Exception e) {
                lastFailure = e;
                log.warn("{} call to {} failed on attempt {}/{}: {}", stage, model, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        return new Outcome(null, record(requestId, stage, model, start, maxAttempts - 1, null, lastFailure));
    }

    private static void backoff(int completedAttempts) {
        long delay = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (completedAttempts - 1)));
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CallRecord record(String requestId, String stage, String model, long start, int retries, ChatResponse response, Exception failure) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens() == null ? null : usage.getPromptTokens().intValue();
            completionTokens = usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().intValue();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String outcome = failure == null ? "success" : "error";
        return new CallRecord(requestId, stage, model, promptTokens, completionTokens, elapsedMs, retries, outcome, failure == null ? null : failure.getMessage());
    }
}
