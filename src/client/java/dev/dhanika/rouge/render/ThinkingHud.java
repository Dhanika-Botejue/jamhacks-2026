package dev.dhanika.rouge.render;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws an animated "Rouge is thinking" progress bar in the top-center of the HUD
 * while the AI is processing a request.
 */
public final class ThinkingHud {

    private static volatile boolean active = false;
    private static volatile long startTime = 0L;

    private ThinkingHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(ThinkingHud::render);
    }

    public static void start() {
        startTime = System.currentTimeMillis();
        active = true;
    }

    public static void stop() {
        active = false;
    }

    private static void render(GuiGraphics graphics, float tickDelta) {
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        long elapsed = System.currentTimeMillis() - startTime;

        int barWidth = 200;
        int barHeight = 8;
        int barX = (screenWidth - barWidth) / 2;
        int barY = 14;

        // Label with animated dots
        String label = "Rouge is thinking" + dots(elapsed);
        int textWidth = mc.font.width(label);
        graphics.drawString(mc.font, label, (screenWidth - textWidth) / 2, barY - 11, 0xFFCCAAFF, false);

        // Track background
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x99000000);

        // Bouncing segment
        int segWidth = barWidth / 4;
        float cycle = (elapsed % 1800) / 1800f;
        float t = cycle < 0.5f ? cycle * 2f : (1f - cycle) * 2f;
        // ease in-out
        t = t * t * (3f - 2f * t);
        int segX = barX + (int)(t * (barWidth - segWidth));
        graphics.fill(segX, barY, segX + segWidth, barY + barHeight, 0xFFAA66FF);

        // Border
        int bx = barX - 1, by = barY - 1, bx2 = barX + barWidth + 1, by2 = barY + barHeight + 1;
        graphics.fill(bx, by,  bx2, by,  0x88DDDDDD); // top
        graphics.fill(bx, by2, bx2, by2 + 1, 0x88DDDDDD); // bottom
        graphics.fill(bx, by,  bx, by2, 0x88DDDDDD);  // left
        graphics.fill(bx2, by, bx2 + 1, by2, 0x88DDDDDD); // right
    }

    private static String dots(long elapsed) {
        int n = (int)((elapsed / 400) % 4);
        return ".".repeat(n);
    }
}
