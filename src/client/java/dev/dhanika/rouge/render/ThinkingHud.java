package dev.dhanika.rouge.render;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws a "Rouge is thinking" progress bar in the top-center of the HUD while the AI
 * is processing a request.
 *
 * Percentage is time-based (asymptotic curve, caps at 99% until the reply arrives).
 * This gives honest live feedback without needing streaming tokens.
 */
public final class ThinkingHud {

    // Time constant for the asymptotic curve: progress = 1 - e^(-elapsed / TAU).
    // At TAU ms the bar is at ~63%; at 2*TAU ~86%; at 3*TAU ~95%.
    // 10 000 ms feels right for typical model response times.
    private static final double TAU_MS = 10_000.0;

    private static volatile boolean active = false;
    private static volatile long startTime = 0L;
    // When stop() is called we briefly show 100% before hiding.
    private static volatile long doneTime = -1L;
    private static final long DONE_LINGER_MS = 400;

    private ThinkingHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(ThinkingHud::render);
    }

    public static void start() {
        startTime = System.currentTimeMillis();
        doneTime = -1L;
        active = true;
    }

    public static void stop() {
        doneTime = System.currentTimeMillis();
    }

    private static void render(GuiGraphics graphics, float tickDelta) {
        if (!active) return;

        long now = System.currentTimeMillis();

        // Hide after the linger period.
        if (doneTime >= 0 && now - doneTime > DONE_LINGER_MS) {
            active = false;
            doneTime = -1L;
            return;
        }

        float pct = doneTime >= 0
                ? 1.0f
                : (float)(1.0 - Math.exp(-(now - startTime) / TAU_MS));
        // Cap in-progress at 99% so the bar visually completes only on real done.
        if (doneTime < 0) pct = Math.min(pct, 0.99f);

        int pctInt = Math.round(pct * 100);

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        int barWidth = 200;
        int barHeight = 8;
        int barX = (screenWidth - barWidth) / 2;
        int barY = 14;

        // Label
        String label = doneTime >= 0 ? "Done!" : "Rouge is thinking…";
        int textWidth = mc.font.width(label);
        graphics.drawString(mc.font, label, (screenWidth - textWidth) / 2, barY - 11, 0xFFCCAAFF, false);

        // Track background
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x99000000);

        // Filled portion
        int filled = (int)(pct * barWidth);
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + barHeight, 0xFFAA66FF);
        }

        // Percentage text centered over the bar
        String pctLabel = pctInt + "%";
        int pctWidth = mc.font.width(pctLabel);
        int pctX = barX + (barWidth - pctWidth) / 2;
        int pctY = barY + (barHeight - mc.font.lineHeight) / 2;
        graphics.drawString(mc.font, pctLabel, pctX, pctY, 0xFFFFFFFF, false);

        // Border
        int bx = barX - 1, by = barY - 1, bx2 = barX + barWidth + 1, by2 = barY + barHeight + 1;
        graphics.fill(bx,  by,  bx2,     by,      0x88DDDDDD);
        graphics.fill(bx,  by2, bx2,     by2 + 1, 0x88DDDDDD);
        graphics.fill(bx,  by,  bx,      by2,     0x88DDDDDD);
        graphics.fill(bx2, by,  bx2 + 1, by2,     0x88DDDDDD);
    }
}
