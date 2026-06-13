package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives an active step-by-step build: tracks the current plan, step index, and a fixed
 * world anchor, and renders each step as an in-world hologram via {@link GhostRenderer}.
 *
 * <p>Steps carry cumulative block lists, so each step shows the whole build-so-far while
 * the blocks added <i>this</i> step are highlighted. The player advances by telling Rouge
 * "next" (handled in the session) rather than typing a command.
 */
public final class StepSession {

    /** Result of advancing, so the session knows whether the build is still running. */
    public enum Advance { MORE, DONE, INACTIVE }

    private static StepPlan plan;
    private static int stepIndex;
    private static BlockPos anchor;
    private static boolean placeMode = false;

    private StepSession() {}

    public static boolean isActive() {
        return plan != null;
    }

    /** Begins a build: anchors it in front of the player and shows step 1. */
    public static void start(StepPlan p) {
        start(p, false);
    }

    /**
     * Begins a build in hologram or place mode. In place mode (creative/singleplayer only)
     * each step's blocks are placed into the world when the player confirms.
     */
    public static void start(StepPlan p, boolean place) {
        int total = p.steps().size();
        if (total == 0) {
            ChatDisplay.system("That build came back empty — ask me to try again, or describe it differently.");
            plan = null;
            return;
        }

        plan = p;
        stepIndex = 0;
        placeMode = place;
        anchor = computeAnchor();

        ChatDisplay.system("Building " + p.circuit() + " — " + total + " step"
                + (total == 1 ? "" : "s") + ". " + locationLine());
        showStep();
    }

    /**
     * Re-places the active build so its footprint is centered in front of the player again,
     * then re-shows the current step. Lets the player move the hologram without restarting.
     */
    public static void recenter() {
        if (plan == null) {
            ChatDisplay.system("No active build to move. Ask me to teach you something first.");
            return;
        }
        anchor = computeAnchor();
        ChatDisplay.system("Moved the hologram. " + locationLine());
        showStep();
    }

    /** Human-readable note of where the hologram is anchored in the world. */
    private static String locationLine() {
        if (anchor == null) return "";
        int[] b = footprint();
        int minX = anchor.getX() + b[0], maxX = anchor.getX() + b[1];
        int minY = anchor.getY() + b[2], maxY = anchor.getY() + b[3];
        int minZ = anchor.getZ() + b[4], maxZ = anchor.getZ() + b[5];
        return "It's floating in front of you, spanning x " + minX + "–" + maxX
                + ", y " + minY + "–" + maxY + ", z " + minZ + "–" + maxZ
                + ". Say \"move\" (or /rouge move) to re-place it where you're standing.";
    }

    /** Advances to the next step (or finishes). Returns whether the build continues. */
    public static Advance next() {
        if (plan == null) {
            ChatDisplay.system("No active build. Ask me to teach you something to start one.");
            return Advance.INACTIVE;
        }
        if (placeMode) {
            List<BlockEntry> toPlace = blocksAddedThisStep();
            if (!WorldPlacer.placeStepBlocks(toPlace, anchor)) {
                ChatDisplay.system("Place mode requires singleplayer. Falling back to hologram mode.");
                placeMode = false;
            }
        }
        stepIndex++;
        if (stepIndex >= plan.steps().size()) {
            String circuit = plan.circuit();
            GhostRenderer.clear();
            plan = null;
            String finish = placeMode
                    ? "That's the whole " + circuit + " — blocks placed! Ask me for another build any time."
                    : "That's the whole " + circuit + " — nice work! The hologram's cleared. Ask me for another build any time.";
            ChatDisplay.system(finish);
            return Advance.DONE;
        }
        showStep();
        return Advance.MORE;
    }

    /** Re-shows the current step (used by /rouge step). */
    public static void showCurrent() {
        if (plan == null) {
            ChatDisplay.system("No active build.");
            return;
        }
        showStep();
    }

    /** Cancels the build and clears the hologram. */
    public static void stop() {
        if (plan == null) return;
        plan = null;
        GhostRenderer.clear();
        ChatDisplay.system("Stopped the build and cleared the hologram. Ping me when you want to pick it back up.");
    }

    public static void reset() {
        plan = null;
        stepIndex = 0;
        anchor = null;
        placeMode = false;
        GhostRenderer.clear();
    }

    /** A one-line reminder of where the player is, for context on mid-build questions. */
    public static String contextLine() {
        if (plan == null) return "";
        StepPlan.Step step = plan.steps().get(stepIndex);
        return "ACTIVE BUILD: " + plan.circuit() + ", step " + (stepIndex + 1) + "/"
                + plan.steps().size() + " (" + step.title() + "). The player is placing these blocks now.";
    }

    private static void showStep() {
        StepPlan.Step step = plan.steps().get(stepIndex);
        int total = plan.steps().size();

        List<BlockEntry> all = step.blocks();
        List<BlockEntry> added = blocksAddedThisStep();

        GhostRenderer.show(anchor, all, added);

        ChatDisplay.system("Step " + (stepIndex + 1) + "/" + total + ": " + step.title());
        if (!step.explanation().isBlank()) {
            ChatDisplay.print(step.explanation());
        }
        if (placeMode) {
            if (stepIndex + 1 < total) {
                ChatDisplay.system("Say \"next\" to place these blocks and continue (or ask me anything).");
            } else {
                ChatDisplay.system("Last step — say \"next\" to place the blocks and finish.");
            }
        } else {
            if (stepIndex + 1 < total) {
                ChatDisplay.system("Place the glowing blocks, then say \"next\" when you're ready (or ask me anything).");
            } else {
                ChatDisplay.system("Last step — place the glowing blocks, then say \"next\" to finish.");
            }
        }
    }

    /** Blocks in the current step that weren't in the previous step (by position). */
    private static List<BlockEntry> blocksAddedThisStep() {
        List<BlockEntry> current = plan.steps().get(stepIndex).blocks();
        if (stepIndex == 0) return current;

        Set<Long> prev = new HashSet<>();
        for (BlockEntry b : plan.steps().get(stepIndex - 1).blocks()) {
            prev.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }
        List<BlockEntry> added = new ArrayList<>();
        for (BlockEntry b : current) {
            if (!prev.contains(BlockPos.asLong(b.x(), b.y(), b.z()))) {
                added.add(b);
            }
        }
        // If nothing is positionally new (e.g. a block-state change), highlight the whole
        // step so the player still gets a visible cue.
        return added.isEmpty() ? current : added;
    }

    /**
     * Anchors the build so its footprint is centered a few blocks in front of the player,
     * resting at foot level. The build's blocks use world-absolute orientation (their
     * blockstate facings are baked in), so we can't rotate it to face the player — instead
     * we translate it to a predictable, visible spot and report where that is.
     */
    private static BlockPos computeAnchor() {
        if (plan == null) return BlockPos.ZERO;
        List<BlockEntry> all = new ArrayList<>();
        for (StepPlan.Step step : plan.steps()) all.addAll(step.blocks());
        return computeAnchorFor(all);
    }

    /**
     * Computes a world anchor for an arbitrary flat block list (e.g. a planning preview).
     * Centers the footprint a few blocks ahead of the player at foot level.
     */
    public static BlockPos computeAnchorFor(List<BlockEntry> blocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return BlockPos.ZERO;

        int[] b = footprintOf(blocks);
        int minX = b[0], maxX = b[1], minY = b[2], minZ = b[4], maxZ = b[5];
        double localCenterX = (minX + maxX) / 2.0;
        double localCenterZ = (minZ + maxZ) / 2.0;

        Direction facing = mc.player.getDirection();
        BlockPos feet = mc.player.blockPosition();

        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int ahead = 2 + Math.max(width, depth) / 2;
        int centerX = feet.getX() + facing.getStepX() * ahead;
        int centerZ = feet.getZ() + facing.getStepZ() * ahead;

        int anchorX = (int) Math.round(centerX - localCenterX);
        int anchorZ = (int) Math.round(centerZ - localCenterZ);
        int anchorY = feet.getY() - minY;
        return new BlockPos(anchorX, anchorY, anchorZ);
    }

    /** Bounding box of the active plan, delegating to footprintOf with all plan blocks. */
    private static int[] footprint() {
        if (plan == null) return new int[]{0, 0, 0, 0, 0, 0};
        List<BlockEntry> all = new ArrayList<>();
        for (StepPlan.Step step : plan.steps()) all.addAll(step.blocks());
        return footprintOf(all);
    }

    /**
     * Bounding box of an arbitrary block list as {@code [minX,maxX, minY,maxY, minZ,maxZ]}.
     * Pass {@code null} to get a zero box.
     */
    private static int[] footprintOf(List<BlockEntry> blocks) {
        if (blocks == null || blocks.isEmpty()) return new int[]{0, 0, 0, 0, 0, 0};
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockEntry e : blocks) {
            minX = Math.min(minX, e.x()); maxX = Math.max(maxX, e.x());
            minY = Math.min(minY, e.y()); maxY = Math.max(maxY, e.y());
            minZ = Math.min(minZ, e.z()); maxZ = Math.max(maxZ, e.z());
        }
        if (minX > maxX) return new int[]{0, 0, 0, 0, 0, 0};
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
}
