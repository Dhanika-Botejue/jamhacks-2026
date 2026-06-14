package dev.dhanika.rouge.ai;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for talking to OpenRouter.
 * <p>
 * API tokens are read once from the {@code OPENROUTER_API_KEY} environment variable — never
 * hardcoded or logged. The variable may hold a SINGLE key or several keys separated by commas,
 * semicolons, or whitespace (e.g. keys from different accounts). When multiple keys are present
 * the client rotates between them on rate-limit/credit/auth failures so the BEST model keeps
 * serving requests instead of downgrading to a weaker one — invisibly to the player.
 * <p>
 * Swap {@link #model} for a different model (including a paid one) in a single line; no other
 * code needs to change.
 */
public final class OpenRouterConfig {

    /** OpenAI-compatible chat completions endpoint. */
    public static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";

    /** Environment variable that holds the OpenRouter API token. */
    public static final String TOKEN_ENV_VAR = "OPENROUTER_API_KEY";

    /**
     * Default text model (chat/tutor). gpt-oss-20b is far better at emitting the strict
     * ```rougebuild``` JSON than the small 7B free models and is less aggressively
     * rate-limited. Free models on OpenRouter come and go; verify the current id at
     * https://openrouter.ai/models, or switch in-game with /rouge model <id>.
     */
    private String model = "openai/gpt-oss-20b:free";

    /**
     * Fallback models tried in order when the primary hits a 429.
     * Each has different rate-limit buckets, so the chain almost never exhausts.
     * Update these (or use /rouge model in-game) if any become unavailable.
     */
    public static final String[] FALLBACKS = {
            "qwen/qwen-2.5-7b-instruct:free",
            "meta-llama/llama-3.1-8b-instruct:free",
            "openchat/openchat-7b:free",
    };

    /** All configured API keys, in the order given. Empty when none are set. */
    private final List<String> tokens;

    /**
     * Index of the key the NEXT request should start with. Rotates forward whenever a key is
     * exhausted and settles on whichever key last succeeded, so we stop hammering a limited key
     * across requests. Atomic because completions resolve on HTTP worker threads.
     */
    private final AtomicInteger preferredKey = new AtomicInteger(0);

    public OpenRouterConfig() {
        this.tokens = parseTokens(System.getenv(TOKEN_ENV_VAR));
    }

    /** Splits the env value into distinct keys on commas, semicolons, or whitespace. */
    private static List<String> parseTokens(String env) {
        if (env == null || env.isBlank()) return Collections.emptyList();
        Set<String> unique = new LinkedHashSet<>(); // preserve order, drop accidental duplicates
        for (String part : env.split("[,;\\s]+")) {
            String key = part.trim();
            if (!key.isEmpty()) unique.add(key);
        }
        return List.copyOf(unique);
    }

    public boolean hasToken() {
        return !tokens.isEmpty();
    }

    /** The primary key — used for one-off calls like model discovery. */
    public String token() {
        return tokens.isEmpty() ? "" : tokens.get(preferredKey.get() % tokens.size());
    }

    /** All configured keys, in order. Never null; may be empty. */
    public List<String> tokens() {
        return tokens;
    }

    /** Number of distinct keys available for rotation. */
    public int tokenCount() {
        return tokens.size();
    }

    /**
     * The key to use for the given rotation offset, starting from the current preferred key and
     * wrapping around, so a request tries every key exactly once across offsets 0..tokenCount()-1.
     */
    public String tokenAtOffset(int offset) {
        if (tokens.isEmpty()) return "";
        return tokens.get((preferredKey.get() + offset) % tokens.size());
    }

    /** Remembers which key just succeeded so the next request starts there. */
    public void rememberPreferredOffset(int offset) {
        if (tokens.isEmpty()) return;
        preferredKey.set((preferredKey.get() + offset) % tokens.size());
    }

    /** A short, key-free label for logging (never prints the secret). */
    public String keyLabel(int offset) {
        if (tokens.isEmpty()) return "none";
        int idx = (preferredKey.get() + offset) % tokens.size();
        return "key#" + (idx + 1) + "/" + tokens.size();
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String endpoint() {
        return ENDPOINT;
    }
}
