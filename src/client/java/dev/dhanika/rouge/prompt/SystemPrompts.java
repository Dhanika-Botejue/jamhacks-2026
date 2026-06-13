package dev.dhanika.rouge.prompt;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads Rouge's system prompt from {@code resources/rouge/system_prompt.txt}.
 * <p>
 * The file is read once from the classpath and cached. Keeping the prompt in a
 * resource (rather than a Java string) makes it easy to edit and swap without
 * recompiling. A short hardcoded fallback is used if the resource is missing.
 */
public final class SystemPrompts {

    private static final String RESOURCE_PATH = "/rouge/system_prompt.txt";

    private static final String FALLBACK =
            "You are Rouge, an expert Minecraft redstone tutor in the player's chat. "
            + "Give concise, practical, buildable redstone advice for modern Java Edition. "
            + "Plain text only.";

    private static String cached;

    private SystemPrompts() {
    }

    public static String redstoneTutor() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    private static String load() {
        try (InputStream in = SystemPrompts.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return FALLBACK;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? FALLBACK : text;
        } catch (Exception e) {
            return FALLBACK;
        }
    }
}
