package dev.dhanika.rouge.build;

import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares the blocks the player has actually placed in the world against the
 * solution. Pure read of the client world — <b>no API calls</b> — so it can run
 * frequently to power proactive tutor nudges and {@code /rouge check}.
 * <p>
 * Comparison is by base block id (properties ignored) to stay lenient about
 * auto-updating states like redstone-dust connections.
 */
public final class BuildDiff {

    public enum Kind { WRONG, MISSING }

    /** One position that doesn't match the solution. */
    public record Mismatch(BlockPos pos, String expected, String found, Kind kind) {
    }

    /** Summary of progress against the solution. */
    public record Report(List<Mismatch> wrong, int correct, int missing, int total) {
        public boolean isComplete() {
            return wrong.isEmpty() && missing == 0;
        }
    }

    private BuildDiff() {
    }

    /** Computes the diff of the world at {@code anchor} against {@code solution}. */
    public static Report compute(BuildSpec solution, BlockPos anchor) {
        ClientLevel level = Minecraft.getInstance().level;
        List<Mismatch> wrong = new ArrayList<>();
        int correct = 0;
        int missing = 0;

        for (BlockEntry b : solution.blocks()) {
            String expected = BuildSpec.baseId(b.block());
            BlockPos pos = anchor.offset(b.x(), b.y(), b.z());
            String found = (level == null) ? "minecraft:air" : idOf(level.getBlockState(pos));

            if (found.equals(expected)) {
                correct++;
            } else if (found.equals("minecraft:air")) {
                missing++;
            } else {
                wrong.add(new Mismatch(pos, expected, found, Kind.WRONG));
            }
        }
        return new Report(wrong, correct, missing, solution.blocks().size());
    }

    private static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
