package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.openai.errors.OpenAIServiceException;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;

/**
 * Issues a single chat completion, selecting the model per call and owning the retry loop.
 * <p>
 * The model name given here is the name sent to the provider, so a recorded {@link CallRecord} cannot
 * disagree with the model that produced the response. Retries are performed here rather than inside the
 * client so that the reported attempt count is accurate; {@code spring.ai.retry.max-attempts} must
 * therefore be 1.
 * <p>
 * Failures are classified before the decision to retry is taken, because only some of them can be fixed
 * by trying again. An expired key or a malformed request fails identically on every attempt, so retrying
 * it wastes calls against a rate-limited endpoint and buries the real cause under repeated log lines.
 * See {@link Kind}.
 */
public final class ChatCall {

    private static final Logger log = LoggerFactory.getLogger(ChatCall.class);

    private static final long INITIAL_BACKOFF_MS = 1_000;

    private static final long MAX_BACKOFF_MS = 30_000;

    private ChatCall() {
    }

    /**
     * Why a call failed, and whether trying again could plausibly help.
     * <p>
     * The name of the constant is what reaches {@link CallRecord#failureCategory()}, so a run report can
     * distinguish a dead network from a rejected key from a throttled endpoint. Recording every failure
     * as transport made that impossible.
     */
    public enum Kind {

        /** 401 or 403. The credential is missing, wrong or inactive; every attempt fails identically. */
        AUTH(false),

        /** A 4xx other than 401, 403, 408 and 429. The request itself is unacceptable to the provider. */
        BAD_REQUEST(false),

        /** 429. The endpoint is throttling; backing off is exactly the right response. */
        RATE_LIMIT(true),

        /** 408, or a client-side read timeout. */
        TIMEOUT(true),

        /** An IO failure, a 5xx, or anything unrecognised. Conservatively treated as retryable. */
        TRANSPORT(true);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /**
     * Result of a call, successful or not.
     *
     * @param response the provider response, or {@code null} when every attempt failed
     * @param record   telemetry for the call, always present
     * @param kind     why the call failed, or {@code null} when it succeeded
     */
    public record Outcome(ChatResponse response, CallRecord record, Kind kind) {

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
     * <p>
     * Permanent failures return after the first attempt rather than consuming {@code maxAttempts}. The
     * recorded retry count therefore reflects the attempts actually made.
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
        Kind lastKind = null;
        int attemptsMade = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsMade = attempt;
            try {
                ChatResponse response = client.prompt().system(system).user(user).options(OpenAiChatOptions.builder().model(model).temperature(temperature)).call().chatResponse();
                return new Outcome(response, record(requestId, stage, model, start, attempt - 1, response, null, null), null);
            }
            catch (Exception e) {
                lastFailure = e;
                lastKind = classify(e);
                log.warn("{} call to {} failed on attempt {}/{} [{}]: {}", stage, model, attempt, maxAttempts, lastKind, e.getMessage());
                if (!lastKind.retryable()) {
                    log.warn("{} is permanent; abandoning after {} attempt(s) instead of retrying", lastKind, attempt);
                    break;
                }
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        return new Outcome(null, record(requestId, stage, model, start, attemptsMade - 1, null, lastFailure, lastKind), lastKind);
    }

    /**
     * Decide whether a recorded failure category is worth another attempt.
     * <p>
     * Categories this class does not own -- the schema and validation failures raised by the generation
     * and filter stages -- are treated as retryable, since resampling can plausibly fix them.
     *
     * @param category a {@link CallRecord#failureCategory()} value, possibly {@code null}
     * @return {@code false} only for categories known to fail identically on every attempt
     */
    public static boolean retryable(String category) {
        if (category == null) {
            return true;
        }
        for (Kind kind : Kind.values()) {
            if (kind.name().equals(category)) {
                return kind.retryable();
            }
        }
        return true;
    }

    /**
     * Classify a failure by walking the cause chain, preferring the provider's HTTP status where one is
     * available and falling back to the IO exception type.
     *
     * @param failure the exception thrown by the call
     * @return the classification; never {@code null}
     */
    private static Kind classify(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 401 || status == 403) {
                    return Kind.AUTH;
                }
                if (status == 429) {
                    return Kind.RATE_LIMIT;
                }
                if (status == 408) {
                    return Kind.TIMEOUT;
                }
                if (status >= 500) {
                    return Kind.TRANSPORT;
                }
                if (status >= 400) {
                    return Kind.BAD_REQUEST;
                }
            }
            // SocketTimeoutException extends InterruptedIOException extends IOException, so the timeout
            // checks must precede the general IO check.
            if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException) {
                return Kind.TIMEOUT;
            }
            if (cause instanceof IOException) {
                return Kind.TRANSPORT;
            }
        }
        return Kind.TRANSPORT;
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

    private static CallRecord record(String requestId, String stage, String model, long start, int retries, ChatResponse response, Exception failure, Kind kind) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens() == null ? null : usage.getPromptTokens().intValue();
            completionTokens = usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().intValue();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String outcome = failure == null ? "success" : "error";
        return new CallRecord(requestId, stage, model, promptTokens, completionTokens, elapsedMs, retries, outcome, failure == null ? null : failure.getMessage(),
                kind == null ? null : kind.name());
    }
}
