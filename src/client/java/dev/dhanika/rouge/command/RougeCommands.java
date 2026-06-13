package dev.dhanika.rouge.command;

import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Registers Rouge's client-side commands.
 * <p>
 * {@code /rouge} is a toggle: first use opens a session, second closes it. Adding
 * a future command (e.g. {@code /rougebuild}) is one more {@code dispatcher.register}
 * line inside the callback below — no other wiring needed.
 */
public final class RougeCommands {

    private RougeCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("rouge")
                        .executes(ctx -> {
                            RougeSession.toggle();
                            return 1;
                        })));
    }
}
