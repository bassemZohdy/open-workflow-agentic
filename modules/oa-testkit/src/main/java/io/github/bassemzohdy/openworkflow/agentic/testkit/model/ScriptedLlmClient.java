package io.github.bassemzohdy.openworkflow.agentic.testkit.model;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A model double for the {@link LlmClient} port: answers come from a script, one step per call, in order.
 *
 * <p>Each step is an answer (optionally after a delay), an injected failure, or a timeout. Every call is recorded
 * with its prompt, outcome, elapsed time and token counts, so a test can assert on what the agent sent.
 * When the script is empty the call uses the fallback responder if one is set; otherwise the call fails with
 * {@link IllegalStateException}, which keeps tests strict.
 *
 * <p>The port returns only text, so token counts are reported through {@link #calls()} and {@link #usage()}.
 * They are the counts given in the script, or an estimate of about four characters per token when not given.
 *
 * <p>Thread-safe: script steps are consumed atomically. Delays happen outside the lock.
 */
public final class ScriptedLlmClient implements LlmClient {

    /** What happened to one call. */
    public enum Outcome { ANSWERED, FAILED, TIMED_OUT, UNSCRIPTED }

    /** One recorded call. Token counts are zero for calls that produced no answer. */
    public record ModelCall(int index, String system, String user, Instant startedAt, Duration elapsed,
                            Outcome outcome, String response, String error, int inputTokens, int outputTokens) {
    }

    /** Totals over the recorded calls. */
    public record Usage(int calls, long inputTokens, long outputTokens) {
    }

    /** One scripted step. The step decides the result of the call it is consumed by. */
    @FunctionalInterface
    interface Step {
        Result run(String system, String user) throws Exception;
    }

    record Result(String text, int inputTokens, int outputTokens) {
    }

    private final Deque<Step> script = new ArrayDeque<>();
    private final List<ModelCall> calls = new ArrayList<>();
    private BiFunction<String, String, String> fallback;

    public static ScriptedLlmClient create() {
        return new ScriptedLlmClient();
    }

    /** The next call returns {@code text}; tokens are estimated from the prompt and the answer. */
    public synchronized ScriptedLlmClient answer(String text) {
        script.add((system, user) -> new Result(text, estimate(system) + estimate(user), estimate(text)));
        return this;
    }

    /** The next call returns {@code text} and reports the given token counts. */
    public synchronized ScriptedLlmClient answer(String text, int inputTokens, int outputTokens) {
        script.add((system, user) -> new Result(text, inputTokens, outputTokens));
        return this;
    }

    /** The next call waits {@code delay}, then returns {@code text}. */
    public synchronized ScriptedLlmClient delayedAnswer(Duration delay, String text) {
        script.add((system, user) -> {
            sleep(delay);
            return new Result(text, estimate(system) + estimate(user), estimate(text));
        });
        return this;
    }

    /** The next call fails with {@code failure}, as if the provider returned an error. */
    public synchronized ScriptedLlmClient fail(RuntimeException failure) {
        script.add((system, user) -> {
            throw failure;
        });
        return this;
    }

    /** The next call does not return within {@code after}: it waits that long, then throws {@link ModelTimeoutException}. */
    public synchronized ScriptedLlmClient timeout(Duration after) {
        script.add((system, user) -> {
            sleep(after);
            throw new ModelTimeoutException("model did not answer within " + after);
        });
        return this;
    }

    /** Used for calls made after the script is empty. Without it such calls fail. */
    public synchronized ScriptedLlmClient fallback(BiFunction<String, String, String> responder) {
        this.fallback = responder;
        return this;
    }

    /** Number of script steps not yet consumed. */
    public synchronized int remaining() {
        return script.size();
    }

    @Override
    public String complete(String system, String user) {
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        Step step;
        int index;
        BiFunction<String, String, String> responder;
        synchronized (this) {
            index = calls.size() + 1;
            step = script.poll();
            responder = fallback;
        }
        Result result;
        try {
            if (step != null) {
                result = step.run(system, user);
            } else if (responder != null) {
                String text = responder.apply(system, user);
                result = new Result(text, estimate(system) + estimate(user), estimate(text));
            } else {
                throw new UnscriptedCallException(index);
            }
        } catch (UnscriptedCallException e) {
            log(index, system, user, started, startNanos, Outcome.UNSCRIPTED, null, e.getMessage(), 0, 0);
            throw e;
        } catch (ModelTimeoutException e) {
            log(index, system, user, started, startNanos, Outcome.TIMED_OUT, null, e.getMessage(), 0, 0);
            throw e;
        } catch (RuntimeException e) {
            log(index, system, user, started, startNanos, Outcome.FAILED, null, e.toString(), 0, 0);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(index, system, user, started, startNanos, Outcome.TIMED_OUT, null, "interrupted", 0, 0);
            throw new ModelTimeoutException("interrupted while waiting for the model");
        } catch (Exception e) {
            log(index, system, user, started, startNanos, Outcome.FAILED, null, e.toString(), 0, 0);
            throw new IllegalStateException(e);
        }
        log(index, system, user, started, startNanos, Outcome.ANSWERED, result.text(), null,
                result.inputTokens(), result.outputTokens());
        return result.text();
    }

    /** Thrown when a call arrives and the script is empty and there is no fallback. */
    public static final class UnscriptedCallException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        UnscriptedCallException(int index) {
            super("scripted model has no step left for call " + index);
        }
    }

    /** Recorded calls in order. */
    public synchronized List<ModelCall> calls() {
        return List.copyOf(calls);
    }

    public synchronized int callCount() {
        return calls.size();
    }

    /** Token totals over the recorded calls that produced an answer. */
    public synchronized Usage usage() {
        long in = 0;
        long out = 0;
        int n = 0;
        for (ModelCall c : calls) {
            n++;
            in += c.inputTokens();
            out += c.outputTokens();
        }
        return new Usage(n, in, out);
    }

    private synchronized void log(int index, String system, String user, Instant started, long startNanos,
                                  Outcome outcome, String response, String error, int in, int out) {
        calls.add(new ModelCall(index, system, user, started, Duration.ofNanos(System.nanoTime() - startNanos),
                outcome, response, error, in, out));
    }

    /** About four characters per token; a heuristic, not a tokenizer. */
    static int estimate(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    private static void sleep(Duration d) throws InterruptedException {
        if (!d.isNegative() && !d.isZero()) {
            Thread.sleep(d.toMillis(), (int) (d.toNanosPart() % 1_000_000));
        }
    }
}
