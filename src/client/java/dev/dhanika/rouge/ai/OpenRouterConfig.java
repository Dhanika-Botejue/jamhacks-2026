package dev.dhanika.rouge.ai;

/**
 * Configuration for talking to OpenRouter.
 * <p>
 * The API token is read once from the {@code OPENROUTER_API_KEY} environment
 * variable — it is never hardcoded or logged. Swap {@link #model} for a different
 * model (including a paid one) in a single line; no other code needs to change.
 */
public final class OpenRouterConfig {

    /** OpenAI-compatible chat completions endpoint. */
    public static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";

    /** Environment variable that holds the OpenRouter API token. */
    public static final String TOKEN_ENV_VAR = "OPENROUTER_API_KEY";

    /**
     * Default model. Free models on OpenRouter come and go and are rate-limited;
     * verify the current id at https://openrouter.ai/models and change it here.
     */
    private String model = "openai/gpt-oss-20b:free";

    private final String token;

    public OpenRouterConfig() {
        String env = System.getenv(TOKEN_ENV_VAR);
        this.token = (env == null) ? "" : env.trim();
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    public String token() {
        return token;
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
