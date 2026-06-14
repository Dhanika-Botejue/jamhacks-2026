package dev.dhanika.rouge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Async client for the OpenRouter chat completions API.
 * <p>
 * Knows nothing about Minecraft — it takes a list of {@link ChatMessage} and
 * returns a {@link CompletableFuture} of the assistant's reply text. The HTTP
 * call runs off the caller's thread via {@link HttpClient#sendAsync}, so it never
 * blocks the game's render thread. Callers are responsible for hopping back onto
 * the main thread before touching Minecraft state.
 */
public final class OpenRouterClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private final OpenRouterConfig config;
    private final HttpClient http;

    public OpenRouterClient(OpenRouterConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                // Short connect timeout so a dead endpoint fails over to a fallback fast;
                // the per-request body timeout below stays generous for slow generations.
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Warms up the client ahead of the first real request: triggers async free-model
     * discovery so the fallback chain is ready, and opens the TLS connection to OpenRouter
     * so the first completion doesn't pay the handshake cost. Safe to call repeatedly.
     */
    public void prewarm() {
        if (!config.hasToken()) return;
        ModelDiscovery.getFreeModels(config.token(), http);
    }

    /** {@link #complete(List, Consumer)} with no status callback. */
    public CompletableFuture<String> complete(List<ChatMessage> history) {
        return complete(history, status -> {});
    }

    /**
     * Sends the conversation to OpenRouter. On any non-200 response (rate limit,
     * no endpoint, unavailable) automatically tries the next model in the chain —
     * first the user-set primary, then every free model discovered live from
     * OpenRouter's API, then the hardcoded emergency fallbacks.
     * <p>
     * {@code onStatus} is notified each time the client falls back to another model, with a
     * human-readable line (the model that failed, why, and what's being tried next). The
     * callback may run on an HTTP worker thread, so callers must hop to their own thread
     * before touching game state.
     */
    public CompletableFuture<String> complete(List<ChatMessage> history, Consumer<String> onStatus) {
        JsonArray messages = new JsonArray();
        for (ChatMessage m : history) {
            messages.add(textMessage(m.role(), m.content()));
        }
        // Kick off model discovery in background if not done yet.
        ModelDiscovery.getFreeModels(config.token(), http);
        return sendWithFallback(messages, 0, 0, onStatus);
    }

    /**
     * Tries each model in the chain; for each model, tries every API key before moving on.
     * <p>
     * The key loop runs FIRST: a rate-limit / out-of-credit / bad-key response (429/402/401) on the
     * best model rotates to the next key on the SAME model, so we keep using the best model as long
     * as any account still has quota. Key switches are silent to the player — logged to the terminal
     * only, never to {@code onStatus} (in-game chat). Only when every key is exhausted for a model do
     * we drop to the next (weaker) model, which is the one event the player may be told about.
     *
     * @param modelIdx index into the model chain
     * @param keyOffset rotation offset into the key list for this model (0..tokenCount()-1)
     */
    private CompletableFuture<String> sendWithFallback(JsonArray messages, int modelIdx, int keyOffset, Consumer<String> onStatus) {
        List<String> chain = buildChain();
        if (modelIdx >= chain.size()) {
            return CompletableFuture.failedFuture(new RuntimeException(
                    "No available models found. Check openrouter.ai/models and use /rouge model <id>."));
        }
        int keyCount = Math.max(1, config.tokenCount());
        if (keyOffset >= keyCount) {
            // Every key was exhausted for this model — drop to the next model, keys reset.
            return sendWithFallback(messages, modelIdx + 1, 0, onStatus);
        }
        String model = chain.get(modelIdx);
        String next = (modelIdx + 1 < chain.size()) ? chain.get(modelIdx + 1) : null;
        String key = config.tokenAtOffset(keyOffset);
        String keyLabel = config.keyLabel(keyOffset);
        boolean moreKeys = keyOffset + 1 < keyCount;
        return sendRaw(model, key, messages, Duration.ofSeconds(30))
                .thenCompose(response -> {
                    logRateLimit(model, keyLabel, response);
                    int status = response.statusCode();
                    if (status != 200) {
                        if (isKeyExhaustion(status) && moreKeys) {
                            // Same best model, next key — invisible to the player (terminal only).
                            LOGGER.info("[Rouge] {} on {} returned HTTP {} — rotating to {} (same model, no interruption).",
                                    keyLabel, model, status, config.keyLabel(keyOffset + 1));
                            return sendWithFallback(messages, modelIdx, keyOffset + 1, onStatus);
                        }
                        if (isKeyExhaustion(status)) {
                            // All keys spent on this model; fall to the next model.
                            LOGGER.info("[Rouge] all {} key(s) limited on {} (HTTP {}).", keyCount, model, status);
                        }
                        notifyFallback(onStatus, model + " returned HTTP " + status, next);
                        return sendWithFallback(messages, modelIdx + 1, 0, onStatus);
                    }
                    try {
                        String reply = parseReply(response);
                        config.rememberPreferredOffset(keyOffset); // start the next request on the key that worked
                        return CompletableFuture.completedFuture(reply);
                    } catch (RuntimeException e) {
                        // Empty/bad response from this model (not a key problem) — try next model.
                        notifyFallback(onStatus, model + " gave a bad reply (" + e.getMessage() + ")", next);
                        return sendWithFallback(messages, modelIdx + 1, 0, onStatus);
                    }
                });
    }

    /**
     * True for HTTP statuses that mean THIS KEY can't serve the request but another key might:
     * 429 (rate limited), 402 (out of credits), 401 (key rejected). These trigger a silent key
     * rotation rather than a model downgrade.
     */
    private static boolean isKeyExhaustion(int status) {
        return status == 429 || status == 402 || status == 401;
    }

    /** Logs (at INFO so it's visible in the dev console) and reports a fallback to the caller. */
    private void notifyFallback(Consumer<String> onStatus, String reason, String next) {
        String line = next != null
                ? reason + " — trying " + next + "…"
                : reason + " — no more models to try.";
        LOGGER.info("[Rouge] {}", line);
        try {
            onStatus.accept(line);
        } catch (Exception ignored) {
            // A misbehaving status sink must never break the request flow.
        }
    }

    /**
     * Logs the model's current rate-limit status to the terminal (dev console) only — never the
     * in-game chat. OpenRouter returns these as {@code X-RateLimit-*} response headers; not every
     * upstream provider sends them, so this is best-effort and silent when they're absent.
     */
    private void logRateLimit(String model, String keyLabel, HttpResponse<?> response) {
        var headers = response.headers();
        var remaining = headers.firstValue("x-ratelimit-remaining");
        var limit = headers.firstValue("x-ratelimit-limit");
        var reset = headers.firstValue("x-ratelimit-reset");
        if (remaining.isEmpty() && limit.isEmpty()) {
            return; // this provider didn't report rate-limit headers
        }
        StringBuilder sb = new StringBuilder();
        sb.append("rate limit [").append(model).append(' ').append(keyLabel).append("]: ")
                .append(remaining.orElse("?")).append('/').append(limit.orElse("?")).append(" remaining");
        reset.flatMap(OpenRouterClient::formatReset)
                .ifPresent(when -> sb.append(", resets ").append(when));
        if (response.statusCode() == 429) {
            sb.append(" — HTTP 429 (limited right now)");
        }
        LOGGER.info("[Rouge] {}", sb);
    }

    /** OpenRouter's reset header is a Unix-epoch milliseconds value; render it as "in Ns". */
    private static java.util.Optional<String> formatReset(String reset) {
        try {
            long resetMs = Long.parseLong(reset.trim());
            long secs = Math.max(0, (resetMs - System.currentTimeMillis()) / 1000);
            return java.util.Optional.of(secs == 0 ? "now" : "in " + secs + "s");
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private List<String> buildChain() {
        List<String> chain = new ArrayList<>();
        // 1. User-set primary.
        chain.add(config.model());
        // 2. Live-discovered free models (populated after first complete() call).
        List<String> discovered = ModelDiscovery.getFreeModels(config.token(), http);
        for (String id : discovered) {
            if (!chain.contains(id)) chain.add(id);
        }
        // 3. Hardcoded emergency fallbacks (in case discovery hasn't finished yet).
        for (String id : OpenRouterConfig.FALLBACKS) {
            if (!chain.contains(id)) chain.add(id);
        }
        return chain;
    }

    private CompletableFuture<HttpResponse<String>> sendRaw(String model, String key, JsonArray messages, Duration timeout) {
        if (key == null || key.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No OpenRouter API key. Set the " + OpenRouterConfig.TOKEN_ENV_VAR + " environment variable."));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 4096);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.endpoint()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    // Optional OpenRouter attribution headers.
                    .header("HTTP-Referer", "https://github.com/dhanika/rouge")
                    .header("X-Title", "Rouge")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return http.sendAsync(request, BodyHandlers.ofString());
    }

    private JsonObject textMessage(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }

    /** Extracts {@code choices[0].message.content}, or throws a readable error. */
    private String parseReply(HttpResponse<String> response) {
        int status = response.statusCode();
        String raw = response.body();

        if (status != 200) {
            throw new RuntimeException(describeError(status, raw));
        }

        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("OpenRouter returned no choices — the model may be overloaded. Try again.");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    ? choice.get("finish_reason").getAsString() : "unknown";

            JsonObject message = choice.getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                // content_filter or tool-call-only response — give a useful hint
                String hint = switch (finishReason) {
                    case "content_filter" -> "The model's content filter blocked the response. Try rephrasing.";
                    case "tool_calls"     -> "The model responded with tool calls instead of text. Try a different model.";
                    default -> "The model returned an empty response (finish_reason=" + finishReason + "). Try again.";
                };
                throw new RuntimeException(hint);
            }
            String content = message.get("content").getAsString().trim();
            if (content.isEmpty()) {
                throw new RuntimeException("The model returned a blank response (finish_reason=" + finishReason + "). Try again.");
            }
            if ("length".equals(finishReason) && !hasCompleteFence(content)) {
                // Cut off at the token limit AND no complete fenced block — any JSON directive is
                // truncated and unusable, so surface it rather than parse garbage. If a fence DID
                // close before the cutoff, the actionable payload is intact, so we keep the content
                // and let the session use it (this salvages a rougefix/rougebuild that finished
                // before the model rambled into the limit).
                throw new RuntimeException("The model's response was cut off (token limit reached). Try asking for a simpler build, or switch models with /rouge model.");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse OpenRouter response: " + e.getMessage());
        }
    }

    /**
     * True when {@code text} contains at least one complete ```` ``` ```` fenced block (an opening
     * fence followed by a closing fence). A truncated response whose fence already closed still
     * carries a usable directive, so it's worth keeping instead of failing over to another model.
     */
    private static boolean hasCompleteFence(String text) {
        if (text == null) return false;
        int open = text.indexOf("```");
        if (open < 0) return false;
        // A closing fence after the opening one means the fenced payload finished before the cutoff.
        return text.indexOf("```", open + 3) > open;
    }

    /** Turns an HTTP error into a short, user-facing message. */
    private String describeError(int status, String raw) {
        String detail = extractErrorMessage(raw);
        String suffix = detail.isEmpty() ? "" : " — " + detail;
        return switch (status) {
            case 401 -> "OpenRouter rejected the API key (401). Check " + OpenRouterConfig.TOKEN_ENV_VAR + "." + suffix;
            case 402 -> "OpenRouter: insufficient credits (402)." + suffix;
            case 429 -> "OpenRouter rate limit hit (429). Wait a moment and try again." + suffix;
            default -> "OpenRouter error (HTTP " + status + ")." + suffix;
        };
    }

    /** Best-effort pull of {@code error.message} from an error body. */
    private String extractErrorMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message") && !error.get("message").isJsonNull()) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Not JSON, or unexpected shape — fall through.
        }
        return "";
    }
}
