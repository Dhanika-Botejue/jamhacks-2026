package dev.dhanika.rouge.ui;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.CircuitLibrary;
import dev.dhanika.rouge.build.CircuitPrimitive;
import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Build picker: opens right after a build prompt, ranks circuits from the JSON library,
 * shows a preview thumbnail per entry, and lets the player click one to build.
 */
public final class CircuitBrowserScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    /** Deferred open: chat close races setScreen if we open in the same frame as send. */
    private static String pendingQuery = null;
    private static int pendingTicks = 0;

    private static final int MARGIN = 16;
    private static final int ROW_H = 46;
    private static final int THUMB = 38;

    private static final int PANEL_BG    = 0xC0101014;
    private static final int ROW_BG      = 0x40000000;
    private static final int ROW_HOVER   = 0x33FFFFFF;
    private static final int SEL_ROW     = 0x4055FF66;
    private static final int SEL_BORDER  = 0xFF55FF66;
    private static final int THUMB_BG    = 0xFF26262E;
    private static final int TITLE_TEXT  = 0xFFFFFFFF;
    private static final int META_TEXT   = 0xFF9AE0A0;
    private static final int DESC_TEXT   = 0xFF9C9CA8;
    private static final int SUBTITLE    = 0xFFD0B0FF;
    private static final int SCROLL_BAR  = 0xFFB070FF;
    private static final int CHECK_GREEN = 0xFF55FF66;
    private static final int CHECK_EMPTY = 0xFF55555E;

    private String selectedId;
    private String query;

    private EditBox searchBox;
    private Button buildButton;
    private List<CircuitPrimitive> filtered = List.of();

    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int scrollOffset;

    public CircuitBrowserScreen(String query) {
        super(Component.literal("Rouge — Pick a Build"));
        this.query = query == null ? "" : query;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CircuitBrowserScreen::tickPendingOpen);
    }

    /** Schedules the build picker — opens once chat has closed, or immediately for /rouge browse. */
    public static void open(String query) {
        pendingQuery = query == null ? "" : query;
        pendingTicks = 0;
        LOGGER.info("[Rouge] Build picker scheduled for \"{}\".", pendingQuery);

        Minecraft mc = Minecraft.getInstance();
        // /rouge browse has no chat screen — open right away on the client thread.
        if (!(mc.screen instanceof ChatScreen)) {
            mc.execute(() -> {
                if (pendingQuery == null) return;
                String q = pendingQuery;
                pendingQuery = null;
                pendingTicks = 0;
                mc.setScreen(new CircuitBrowserScreen(q));
                LOGGER.info("[Rouge] Build picker opened for \"{}\".", q);
            });
        }
    }

    private static void tickPendingOpen(Minecraft mc) {
        if (pendingQuery == null || mc.player == null) return;
        pendingTicks++;
        // Give ChatScreen time to close after ALLOW_CHAT cancels the outgoing message.
        if (pendingTicks < 2) return;
        if (mc.screen instanceof ChatScreen && pendingTicks < 15) return;

        String q = pendingQuery;
        pendingQuery = null;
        pendingTicks = 0;
        mc.setScreen(new CircuitBrowserScreen(q));
        LOGGER.info("[Rouge] Build picker opened for \"{}\".", q);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected void init() {
        int top = 40;
        searchBox = new EditBox(font, MARGIN, top, width - 2 * MARGIN, 18, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search builds — e.g. \"piston door\", \"clock\"…"));
        searchBox.setValue(query);
        searchBox.setResponder(this::refilter);
        addRenderableWidget(searchBox);

        listLeft = MARGIN;
        listRight = width - MARGIN;
        listTop = top + 24;
        listBottom = height - 38;

        refilter(query);

        int gap = 8;
        int btnW = (width - 2 * MARGIN - 2 * gap) / 3;
        int by = height - 28;
        int x0 = MARGIN;
        buildButton = Button.builder(buildLabel(), b -> onBuild())
                .bounds(x0, by, btnW, 20).build();
        addRenderableWidget(buildButton);
        addRenderableWidget(Button.builder(Component.literal("Let Rouge choose"), b -> onLetRougeChoose())
                .bounds(x0 + btnW + gap, by, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x0 + 2 * (btnW + gap), by, btnW, 20).build());
        updateBuildButton();
        setInitialFocus(searchBox);
    }

    private void refilter(String q) {
        this.query = q;
        filtered = CircuitLibrary.ranked(q);
        clampScroll();
    }

    private void onBuild() {
        if (selectedId == null) return;
        CircuitPrimitive p = CircuitLibrary.get(selectedId);
        if (p == null) return;
        String goal = searchBox.getValue();
        onClose();
        RougeSession.buildSelected(p, goal);
    }

    private void onLetRougeChoose() {
        String goal = searchBox.getValue();
        onClose();
        RougeSession.buildWithAi(goal);
    }

    /** Single-select: click a row to pick exactly one design. */
    private void select(String id) {
        selectedId = id;
        updateBuildButton();
    }

    private void updateBuildButton() {
        buildButton.setMessage(buildLabel());
        buildButton.active = selectedId != null;
    }

    private Component buildLabel() {
        if (selectedId == null) return Component.literal("Build this");
        CircuitPrimitive p = CircuitLibrary.get(selectedId);
        String name = p != null ? p.title() : selectedId;
        return Component.literal("Build: " + name);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, TITLE_TEXT);
        g.drawCenteredString(font,
                Component.literal("Click a design — closest matches from the library are listed first."),
                width / 2, 26, SUBTITLE);

        renderList(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(listLeft, listTop, listRight, listBottom, PANEL_BG);

        if (filtered.isEmpty()) {
            g.drawCenteredString(font, Component.literal("No matching builds — try a different search."),
                    (listLeft + listRight) / 2, (listTop + listBottom) / 2 - 4, META_TEXT);
            return;
        }

        g.enableScissor(listLeft, listTop, listRight, listBottom);
        int y = listTop - scrollOffset;
        for (CircuitPrimitive p : filtered) {
            if (y + ROW_H >= listTop && y <= listBottom) {
                renderRow(g, p, y, mouseX, mouseY);
            }
            y += ROW_H;
        }
        g.disableScissor();

        renderScrollbar(g);
    }

    private void renderRow(GuiGraphics g, CircuitPrimitive p, int y, int mouseX, int mouseY) {
        boolean sel = p.id().equals(selectedId);
        boolean hover = mouseX >= listLeft && mouseX <= listRight
                && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);

        int top = y + 2, bottom = y + ROW_H - 2;
        g.fill(listLeft + 2, top, listRight - 2, bottom, sel ? SEL_ROW : ROW_BG);
        if (hover && !sel) g.fill(listLeft + 2, top, listRight - 2, bottom, ROW_HOVER);
        if (sel) {
            g.fill(listLeft + 2, top, listRight - 2, top + 1, SEL_BORDER);
            g.fill(listLeft + 2, bottom - 1, listRight - 2, bottom, SEL_BORDER);
            g.fill(listLeft + 2, top, listLeft + 3, bottom, SEL_BORDER);
            g.fill(listRight - 3, top, listRight - 2, bottom, SEL_BORDER);
        }

        int thumbX = listLeft + 6, thumbY = y + (ROW_H - THUMB) / 2;
        g.fill(thumbX, thumbY, thumbX + THUMB, thumbY + THUMB, THUMB_BG);
        List<BlockEntry> blocks = p.blocks();
        renderThumb(g, blocks, thumbX, thumbY);

        int textX = thumbX + THUMB + 8;
        int textRight = listRight - 22;
        g.drawString(font, p.title(), textX, y + 6, TITLE_TEXT, false);
        String kind = p.isBuildable() ? "buildable" : "blueprint";
        String meta = kind + "   " + p.footprint();
        if (p.isBuildable()) {
            meta += "   " + p.steps().size() + " steps   " + blocks.size() + " blocks";
        }
        g.drawString(font, meta, textX, y + 18, META_TEXT, false);
        String desc = font.plainSubstrByWidth(p.description(), textRight - textX);
        g.drawString(font, desc, textX, y + 30, DESC_TEXT, false);

        int cb = listRight - 18, cy = y + (ROW_H - 12) / 2;
        g.fill(cb, cy, cb + 12, cy + 12, sel ? CHECK_GREEN : CHECK_EMPTY);
        g.fill(cb + 1, cy + 1, cb + 11, cy + 11, sel ? CHECK_GREEN : THUMB_BG);
        if (sel) {
            g.fill(cb + 3, cy + 6, cb + 5, cy + 9, 0xFF0A2A0A);
            g.fill(cb + 5, cy + 4, cb + 9, cy + 9, 0xFF0A2A0A);
        }
    }

    /** Renders block item icons from JSON block data (up to 9 unique types). */
    private void renderThumb(GuiGraphics g, List<BlockEntry> blocks, int thumbX, int thumbY) {
        if (blocks == null || blocks.isEmpty()) {
            g.drawCenteredString(font, Component.literal("BP"), thumbX + THUMB / 2, thumbY + THUMB / 2 - 4, META_TEXT);
            return;
        }

        Set<String> unique = new LinkedHashSet<>();
        for (BlockEntry b : blocks) {
            unique.add(baseBlockId(b.block()));
            if (unique.size() >= 9) break;
        }

        int icon = 11;
        int cols = Math.min(3, unique.size());
        int rows = (unique.size() + cols - 1) / cols;
        int gridW = cols * icon + (cols - 1) * 2;
        int gridH = rows * icon + (rows - 1) * 2;
        int startX = thumbX + (THUMB - gridW) / 2;
        int startY = thumbY + (THUMB - gridH) / 2;

        int i = 0;
        for (String blockId : unique) {
            ItemStack stack = blockToStack(blockId);
            if (stack.isEmpty()) continue;
            int col = i % cols;
            int row = i / cols;
            int ix = startX + col * (icon + 2);
            int iy = startY + row * (icon + 2);
            g.renderItem(stack, ix, iy);
            g.renderItemDecorations(font, stack, ix, iy);
            i++;
        }
    }

    private static String baseBlockId(String block) {
        if (block == null || block.isBlank()) return "minecraft:stone";
        int bracket = block.indexOf('[');
        return bracket >= 0 ? block.substring(0, bracket) : block;
    }

    private static ItemStack blockToStack(String blockId) {
        ResourceLocation loc = ResourceLocation.tryParse(blockId);
        if (loc == null) return ItemStack.EMPTY;
        Block block = BuiltInRegistries.BLOCK.get(loc);
        if (block == null) return ItemStack.EMPTY;
        return new ItemStack(block);
    }

    private void renderScrollbar(GuiGraphics g) {
        int viewH = listBottom - listTop;
        int contentH = filtered.size() * ROW_H;
        if (contentH <= viewH) return;
        int barX = listRight - 3;
        int barH = Math.max(20, (int) ((long) viewH * viewH / contentH));
        int travel = viewH - barH;
        int maxScroll = contentH - viewH;
        int barY = listTop + (maxScroll == 0 ? 0 : (int) ((long) scrollOffset * travel / maxScroll));
        g.fill(barX, barY, barX + 2, barY + barH, SCROLL_BAR);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Handle list clicks before widgets so rows are always clickable.
        if (button == 0 && mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom
                && !filtered.isEmpty()) {
            int rel = (int) (my - listTop) + scrollOffset;
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < filtered.size()) {
                select(filtered.get(idx).id());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom) {
            scrollOffset -= (int) (delta * (ROW_H / 2.0));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selectedId != null && (keyCode == 257 || keyCode == 335)) { // Enter / numpad enter
            onBuild();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, filtered.size() * ROW_H - (listBottom - listTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }
}
