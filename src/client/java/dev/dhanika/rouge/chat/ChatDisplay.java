package dev.dhanika.rouge.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Prints Rouge messages into the player's own chat HUD (client-side only —
 * nothing is sent to the server).
 * <p>
 * All methods must be called on the main client thread. Long replies are split
 * on newlines so each line renders cleanly in the chat box.
 */
public final class ChatDisplay {

    private static final String PREFIX = "[Rouge] ";

    private ChatDisplay() {
    }

    /** A normal Rouge reply, in light purple. */
    public static void print(String message) {
        emit(message, ChatFormatting.LIGHT_PURPLE);
    }

    /** A system/status note (e.g. session opened, thinking…), in gray. */
    public static void system(String message) {
        emit(message, ChatFormatting.GRAY);
    }

    /** An error line, in red. */
    public static void error(String message) {
        emit(message, ChatFormatting.RED);
    }

    /** The user's own message, echoed in aqua so it's visible alongside Rouge's replies. */
    public static void userSaid(String message) {
        emit("[You] " + message, ChatFormatting.AQUA);
    }

    /** A thought/reasoning excerpt from the model (from &lt;think&gt; blocks), in dark gray italic. */
    public static void thought(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null || message == null) return;
        for (String line : message.split("\n", -1)) {
            if (line.isBlank()) continue;
            client.gui.getChat().addMessage(
                    Component.literal("[Rouge thinks] " + line)
                            .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(true)));
        }
    }

    private static void emit(String message, ChatFormatting color) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null || message == null) {
            return;
        }
        for (String line : message.split("\n", -1)) {
            client.gui.getChat().addMessage(
                    Component.literal(PREFIX + line).withStyle(color));
        }
    }
}
