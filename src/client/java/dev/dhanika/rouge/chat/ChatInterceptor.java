package dev.dhanika.rouge.chat;

import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

/**
 * Routes the player's outgoing chat to Rouge while a session is open.
 * <p>
 * Uses {@link ClientSendMessageEvents#ALLOW_CHAT}: returning {@code false}
 * cancels the message before it reaches the server. This event fires only for
 * chat, never for commands, so {@code /rouge} always works to close the session
 * — the player can't get locked out.
 * <p>
 * Fails open: if anything throws, the message is allowed through as normal chat.
 */
public final class ChatInterceptor {

    private ChatInterceptor() {
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!RougeSession.isOpen()) {
                return true; // Normal chat — untouched.
            }
            try {
                RougeSession.handleUserMessage(message);
            } catch (Throwable t) {
                ChatDisplay.error("Rouge failed to handle your message: " + t.getMessage());
            }
            return false; // Suppress from public chat; Rouge handled it.
        });
    }
}
