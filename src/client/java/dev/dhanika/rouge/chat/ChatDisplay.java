package dev.dhanika.rouge.chat;

import dev.dhanika.rouge.build.PlanningOutline;
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

    /** A planning outline, in gold. Shows circuit name, parts, and the AI's refinement prompt. */
    public static void plan(PlanningOutline outline) {
        String header = "Planning: " + outline.circuit()
                + (outline.footprint().isBlank() ? "" : " (" + outline.footprint() + ")");
        emit(header, ChatFormatting.GOLD);
        for (PlanningOutline.Part part : outline.parts()) {
            emit(" • " + part.name() + " — " + part.description(), ChatFormatting.GOLD);
        }
        if (!outline.notes().isBlank()) {
            emit(outline.notes(), ChatFormatting.GOLD);
        }
        emit("Say \"build it\" when ready, or describe what to change.", ChatFormatting.GOLD);
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
