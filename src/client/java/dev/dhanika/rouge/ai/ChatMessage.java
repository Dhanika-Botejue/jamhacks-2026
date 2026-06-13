package dev.dhanika.rouge.ai;

/**
 * One message in a chat completion conversation.
 * <p>
 * Roles follow the OpenAI/OpenRouter convention: {@code system}, {@code user},
 * {@code assistant}. This record is intentionally free of any Minecraft types so
 * the {@code ai} package can be reused by future features.
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
