package dev.dhanika.rouge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Standalone {@code /fix} command: describe what's going wrong and Rouge diagnoses it from a live
 * signal trace and applies the repair automatically — no separate "fix it" confirmation needed.
 */
public final class FixCommands {

    private FixCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("fix")
                        .then(ClientCommandManager.argument("issue", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    RougeSession.askFix(StringArgumentType.getString(ctx, "issue"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ChatDisplay.system("Usage: /fix <what's going wrong>  — I'll diagnose and repair it automatically.");
                            return 1;
                        })));
    }
}
