package dev.dhanika.rouge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    private final OpenRouterConfig config;
    private final HttpClient http;

    public OpenRouterClient(OpenRouterConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Sends the conversation to OpenRouter and resolves with the reply text.
     * The returned future completes exceptionally (with a human-readable message)
     * on missing token, non-200 status, or malformed response.
     */
    public CompletableFuture<String> complete(List<ChatMessage> history) {
        if (!config.hasToken()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No OpenRouter API key. Set the " + OpenRouterConfig.TOKEN_ENV_VAR + " environment variable."));
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.endpoint()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    // Optional OpenRouter attribution headers.
                    .header("HTTP-Referer", "https://github.com/dhanika/rouge")
                    .header("X-Title", "Rouge")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(history)))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return http.sendAsync(request, BodyHandlers.ofString())
                .thenApply(this::parseReply);
    }

    /** Builds the OpenAI-style request body: {@code { model, messages: [...] }}. */
    private String buildBody(List<ChatMessage> history) {
        JsonArray messages = new JsonArray();
        for (ChatMessage m : history) {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", m.role());
            obj.addProperty("content", m.content());
            messages.add(obj);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", messages);
        return body.toString();
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
                throw new RuntimeException("OpenRouter returned no choices.");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new RuntimeException("OpenRouter reply had no content.");
            }
            return message.get("content").getAsString().trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse OpenRouter response: " + e.getMessage());
        }
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
