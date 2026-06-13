package dev.dhanika.rouge.ui;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.CircuitLibrary;
import dev.dhanika.rouge.build.CircuitPrimitive;
import dev.dhanika.rouge.session.RougeSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The build browser: a visual selection window over Rouge's library of buildable circuits.
 * Each match is shown as a clickable tile with an isometric preview, name, stats, and
 * description. Click (or Space) to toggle selection — multiple circuits can be selected
 * and stitched together. A persistent selection tray at the bottom shows what's queued.
 */
public final class CircuitBrowserScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private static final int MARGIN = 16;
    private static final int ROW_H  = 46;
    private static final int THUMB  = 38;
    private static final int TRAY_H = 52; // selection tray height at bottom

    // Colors (ARGB).
    private static final int PANEL_BG    = 0xC0101014;
    private static final int TRAY_BG     = 0xD0181820;
    private static final int TRAY_BORDER = 0xFF6040C0;
    private static final int ROW_BG      = 0x40000000;
    private static final int ROW_HOVER   = 0x33FFFFFF;
    private static final int SEL_ROW     = 0x5055FF66;
    private static final int SEL_BORDER  = 0xFF55FF66;
    private static final int THUMB_BG    = 0xFF26262E;
    private static final int TITLE_TEXT  = 0xFFFFFFFF;
    private static final int META_TEXT   = 0xFF9AE0A0;
    private static final int DESC_TEXT   = 0xFF9C9CA8;
    private static final int SUBTITLE    = 0xFFD0B0FF;
    private static final int HINT_TEXT   = 0xFF7060A0;
    private static final int SCROLL_BAR  = 0xFFB070FF;
    private static final int CHECK_GREEN = 0xFF55FF66;
    private static final int CHECK_EMPTY = 0xFF55555E;
    private static final int CHIP_BG     = 0xFF2A2040;
    private static final int CHIP_SEL    = 0xFF3A4A2A;
    private static final int CHIP_BORDER = 0xFF55FF66;

    private final Set<String> selected = new LinkedHashSet<>();
    private String query;

    private EditBox searchBox;
    private Button stitchButton;
    private Button clearButton;
    private List<CircuitPrimitive> filtered = List.of();

    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int scrollOffset;

    // Keyboard focus index within the filtered list (-1 = none).
    private int focusIdx = -1;

    public CircuitBrowserScreen(String query) {
        super(Component.literal("Rouge — Pick Parts to Stitch"));
        this.query = query == null ? "" : query;
    }

    /** Opens the browser on the client thread, seeded with a search query. */
    public static void open(String query) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new CircuitBrowserScreen(query)));
    }

    /** Registers screen-open key feedback — called from RougeClient. */
    public static void register() {
        // No global key binding needed; the screen is opened via chat routing.
    }

    @Override
    protected void init() {
        int searchY = 40;
        searchBox = new EditBox(font, MARGIN, searchY, width - 2 * MARGIN, 18, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search builds — e.g. \"piston door\", \"clock\"…"));
        searchBox.setValue(query);
        searchBox.setResponder(this::refilter);
        addRenderableWidget(searchBox);

        listLeft   = MARGIN;
        listRight  = width - MARGIN;
        listTop    = searchY + 24;
        listBottom = height - TRAY_H - 8;

        refilter(query);

        // Buttons inside the tray.
        int btnY  = height - TRAY_H + 30;
        int btnW  = 140;
        int gap   = 8;
        int totalW = btnW * 3 + gap * 2;
        int x0    = (width - totalW) / 2;

        stitchButton = Button.builder(stitchLabel(), b -> onStitch())
                .bounds(x0, btnY, btnW, 20).build();
        addRenderableWidget(stitchButton);

        addRenderableWidget(Button.builder(Component.literal("Let Rouge choose"), b -> onLetRougeChoose())
                .bounds(x0 + btnW + gap, btnY, btnW, 20).build());

        clearButton = Button.builder(Component.literal("Clear selection"), b -> onClear())
                .bounds(x0 + 2 * (btnW + gap), btnY, btnW, 20).build();
        addRenderableWidget(clearButton);

        updateButtons();
        setInitialFocus(searchBox);
    }

    private void refilter(String q) {
        this.query = q;
        filtered = CircuitLibrary.rankedBuildable(q);
        focusIdx = filtered.isEmpty() ? -1 : 0;
        clampScroll();
    }

    private void onStitch() {
        List<CircuitPrimitive> parts = new ArrayList<>();
        for (String id : selected) {
            CircuitPrimitive p = CircuitLibrary.get(id);
            if (p != null && p.isBuildable()) parts.add(p);
        }
        if (parts.isEmpty()) return;
        String goal = searchBox.getValue();
        LOGGER.info("[Rouge] Stitch: {} part(s) {}", parts.size(), parts.stream().map(CircuitPrimitive::id).toList());
        onClose();
        RougeSession.stitchSelected(goal, parts);
    }

    private void onLetRougeChoose() {
        String goal = searchBox.getValue();
        onClose();
        RougeSession.buildWithAi(goal);
    }

    private void onClear() {
        selected.clear();
        updateButtons();
    }

    private void toggleItem(String id) {
        if (!selected.remove(id)) selected.add(id);
        updateButtons();
    }

    private void updateButtons() {
        stitchButton.setMessage(stitchLabel());
        stitchButton.active = !selected.isEmpty();
        clearButton.active  = !selected.isEmpty();
    }

    private Component stitchLabel() {
        if (selected.isEmpty()) return Component.literal("Stitch selected");
        return Component.literal("Stitch " + selected.size() + " circuit" + (selected.size() == 1 ? "" : "s") + " →");
    }

    // --- rendering ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        // Header
        g.drawCenteredString(font, title, width / 2, 12, TITLE_TEXT);
        g.drawCenteredString(font,
                Component.literal("Click circuits to select — pick as many as you want, then stitch them together."),
                width / 2, 26, SUBTITLE);

        renderList(g, mouseX, mouseY);
        renderTray(g);

        super.render(g, mouseX, mouseY, partialTick); // widgets on top
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
        for (int i = 0; i < filtered.size(); i++) {
            CircuitPrimitive p = filtered.get(i);
            if (y + ROW_H >= listTop && y <= listBottom) {
                renderRow(g, p, y, mouseX, mouseY, i == focusIdx);
            }
            y += ROW_H;
        }
        g.disableScissor();

        renderScrollbar(g);

        // Keyboard hint bottom-right of list area
        g.drawString(font, "Space / click to select  ·  Enter to stitch",
                listRight - font.width("Space / click to select  ·  Enter to stitch") - 4,
                listBottom - font.lineHeight - 2, HINT_TEXT, false);
    }

    private void renderRow(GuiGraphics g, CircuitPrimitive p, int y, int mouseX, int mouseY, boolean focused) {
        boolean sel   = selected.contains(p.id());
        boolean hover = mouseX >= listLeft && mouseX <= listRight
                && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);

        int top = y + 2, bottom = y + ROW_H - 2;
        g.fill(listLeft + 2, top, listRight - 2, bottom, sel ? SEL_ROW : ROW_BG);
        if (hover && !sel) g.fill(listLeft + 2, top, listRight - 2, bottom, ROW_HOVER);

        // Selection border
        if (sel) {
            g.fill(listLeft + 2, top,    listRight - 2, top + 1,    SEL_BORDER);
            g.fill(listLeft + 2, bottom - 1, listRight - 2, bottom, SEL_BORDER);
            g.fill(listLeft + 2, top,    listLeft + 3,  bottom,     SEL_BORDER);
            g.fill(listRight - 3, top,   listRight - 2, bottom,     SEL_BORDER);
        }

        // Keyboard focus ring
        if (focused && !sel) {
            int fc = 0x66AA88FF;
            g.fill(listLeft + 2, top, listRight - 2, top + 1, fc);
            g.fill(listLeft + 2, bottom - 1, listRight - 2, bottom, fc);
        }

        // Isometric thumbnail
        int thumbX = listLeft + 6, thumbY = y + (ROW_H - THUMB) / 2;
        g.fill(thumbX, thumbY, thumbX + THUMB, thumbY + THUMB, THUMB_BG);
        try {
            renderIso(g, p.blocks(), thumbX + 2, thumbY + 2, THUMB - 4, THUMB - 4);
        } catch (Exception ignored) {}

        // Text block
        int textX    = thumbX + THUMB + 8;
        int textRight = listRight - 22;
        g.drawString(font, p.title(), textX, y + 6, TITLE_TEXT, false);
        String meta = p.footprint() + "   " + p.steps().size() + " steps   " + p.blocks().size() + " blocks";
        g.drawString(font, meta, textX, y + 18, META_TEXT, false);
        String desc = font.plainSubstrByWidth(p.description(), textRight - textX);
        g.drawString(font, desc, textX, y + 30, DESC_TEXT, false);

        // Checkbox
        int cb = listRight - 18, cy = y + (ROW_H - 12) / 2;
        g.fill(cb, cy, cb + 12, cy + 12, sel ? CHECK_GREEN : CHECK_EMPTY);
        g.fill(cb + 1, cy + 1, cb + 11, cy + 11, sel ? CHECK_GREEN : THUMB_BG);
        if (sel) {
            g.fill(cb + 3, cy + 6, cb + 5, cy + 9, 0xFF0A2A0A);
            g.fill(cb + 5, cy + 4, cb + 9, cy + 9, 0xFF0A2A0A);
        }
    }

    /** Selection tray — shows chips for each selected circuit name and the action buttons. */
    private void renderTray(GuiGraphics g) {
        int ty = height - TRAY_H;
        g.fill(listLeft, ty, listRight, height - 2, TRAY_BG);
        // Top border line
        g.fill(listLeft, ty, listRight, ty + 1, TRAY_BORDER);

        if (selected.isEmpty()) {
            g.drawCenteredString(font,
                    Component.literal("No circuits selected — click rows above to queue them for stitching."),
                    width / 2, ty + 8, HINT_TEXT);
        } else {
            // "X selected:" label
            String countLabel = selected.size() + " selected:";
            g.drawString(font, countLabel, listLeft + 4, ty + 8, CHECK_GREEN, false);
            int chipX = listLeft + 4 + font.width(countLabel) + 6;
            int chipY = ty + 4;
            for (String id : selected) {
                CircuitPrimitive p = CircuitLibrary.get(id);
                String name = p != null ? p.title() : id;
                int chipW = font.width(name) + 10;
                if (chipX + chipW > listRight - 4) break; // overflow guard
                g.fill(chipX, chipY, chipX + chipW, chipY + font.lineHeight + 4,
                        selected.contains(id) ? CHIP_SEL : CHIP_BG);
                g.fill(chipX, chipY, chipX + chipW, chipY + 1, CHIP_BORDER);
                g.fill(chipX, chipY + font.lineHeight + 3, chipX + chipW, chipY + font.lineHeight + 4, CHIP_BORDER);
                g.fill(chipX, chipY, chipX + 1, chipY + font.lineHeight + 4, CHIP_BORDER);
                g.fill(chipX + chipW - 1, chipY, chipX + chipW, chipY + font.lineHeight + 4, CHIP_BORDER);
                g.drawString(font, name, chipX + 5, chipY + 2, TITLE_TEXT, false);
                chipX += chipW + 4;
            }
        }
    }

    /** Draws a small isometric voxel preview of {@code blocks} inside the given box using fills. */
    private void renderIso(GuiGraphics g, List<BlockEntry> blocks, int bx, int by, int bw, int bh) {
        if (blocks == null || blocks.isEmpty()) return;

        double minPx = Double.MAX_VALUE, maxPx = -Double.MAX_VALUE;
        double minPy = Double.MAX_VALUE, maxPy = -Double.MAX_VALUE;
        for (BlockEntry b : blocks) {
            double px = b.x() - b.z();
            double py = (b.x() + b.z()) / 2.0 - b.y();
            minPx = Math.min(minPx, px); maxPx = Math.max(maxPx, px);
            minPy = Math.min(minPy, py); maxPy = Math.max(maxPy, py);
        }
        double spanX = Math.max(maxPx - minPx, 0.001);
        double spanY = Math.max(maxPy - minPy, 0.001);
        double scale = Math.min((bw - 2) / (spanX + 1), (bh - 2) / (spanY + 1));
        scale = Mth.clamp(scale, 1.0, 7.0);
        int cell = (int) Math.ceil(scale) + 1;

        double contentW = spanX * scale, contentH = spanY * scale;
        double offX = bx + (bw - contentW) / 2.0 - minPx * scale;
        double offY = by + (bh - contentH) / 2.0 - minPy * scale;

        List<BlockEntry> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b) -> Integer.compare(a.x() + a.y() + a.z(), b.x() + b.y() + b.z()));

        for (BlockEntry b : sorted) {
            double px = b.x() - b.z();
            double py = (b.x() + b.z()) / 2.0 - b.y();
            int sx = (int) Math.round(offX + px * scale);
            int sy = (int) Math.round(offY + py * scale);
            int color = colorFor(b.block());
            g.fill(sx, sy, sx + cell, sy + cell, color);
            g.fill(sx, sy, sx + cell, sy + 1,           lighten(color, 50));
            g.fill(sx, sy + cell - 1, sx + cell, sy + cell, lighten(color, -40));
        }
    }

    private static int colorFor(String block) {
        String b = block == null ? "" : block.toLowerCase();
        if (b.contains("redstone_wire") || b.equals("minecraft:redstone")) return 0xFFE03434;
        if (b.contains("redstone_torch") || b.contains("redstone_wall_torch") || b.contains("torch")) return 0xFFFF5A3C;
        if (b.contains("repeater") || b.contains("comparator")) return 0xFFC07878;
        if (b.contains("redstone_lamp"))  return 0xFFE7C766;
        if (b.contains("sticky_piston"))  return 0xFF9FB04A;
        if (b.contains("piston"))         return 0xFFB89A5C;
        if (b.contains("slime"))          return 0xFF7BC043;
        if (b.contains("honey"))          return 0xFFE0A832;
        if (b.contains("observer"))       return 0xFF5E6068;
        if (b.contains("target"))         return 0xFFE6CCA6;
        if (b.contains("lever") || b.contains("button")) return 0xFF8A8A8A;
        if (b.contains("dropper") || b.contains("dispenser") || b.contains("hopper")) return 0xFF50505A;
        if (b.contains("glass"))          return 0xFFAFD3E2;
        if (b.contains("note_block") || b.contains("wool") || b.contains("planks") || b.contains("log")) return 0xFFA9794C;
        if (b.contains("stone") || b.contains("cobble") || b.contains("brick") || b.contains("deepslate")
                || b.contains("concrete") || b.contains("terracotta") || b.contains("smooth")) return 0xFF8C8C92;
        return 0xFF7A7A82;
    }

    private static int lighten(int argb, int delta) {
        int a  = (argb >>> 24) & 0xFF;
        int r  = Mth.clamp(((argb >> 16) & 0xFF) + delta, 0, 255);
        int gg = Mth.clamp(((argb >>  8) & 0xFF) + delta, 0, 255);
        int bb = Mth.clamp((argb         & 0xFF) + delta, 0, 255);
        return (a << 24) | (r << 16) | (gg << 8) | bb;
    }

    private void renderScrollbar(GuiGraphics g) {
        int viewH    = listBottom - listTop;
        int contentH = filtered.size() * ROW_H;
        if (contentH <= viewH) return;
        int barX  = listRight - 3;
        int barH  = Math.max(20, (int)((long) viewH * viewH / contentH));
        int travel    = viewH - barH;
        int maxScroll = contentH - viewH;
        int barY  = listTop + (maxScroll == 0 ? 0 : (int)((long) scrollOffset * travel / maxScroll));
        g.fill(barX, barY, barX + 2, barY + barH, SCROLL_BAR);
    }

    // --- input ---

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom
                && !filtered.isEmpty()) {
            int rel = (int)(my - listTop) + scrollOffset;
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < filtered.size()) {
                focusIdx = idx;
                toggleItem(filtered.get(idx).id());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Don't steal typing from the search box.
        if (searchBox.isFocused()) return super.keyPressed(keyCode, scanCode, modifiers);

        switch (keyCode) {
            case 264 -> { // Down arrow
                focusIdx = Math.min(focusIdx + 1, filtered.size() - 1);
                scrollToFocused();
                return true;
            }
            case 265 -> { // Up arrow
                focusIdx = Math.max(focusIdx - 1, 0);
                scrollToFocused();
                return true;
            }
            case 32 -> { // Space — toggle focused
                if (focusIdx >= 0 && focusIdx < filtered.size()) {
                    toggleItem(filtered.get(focusIdx).id());
                }
                return true;
            }
            case 257, 335 -> { // Enter / numpad Enter — stitch
                if (!selected.isEmpty()) onStitch();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void scrollToFocused() {
        if (focusIdx < 0 || filtered.isEmpty()) return;
        int itemTop = focusIdx * ROW_H;
        int viewH   = listBottom - listTop;
        if (itemTop < scrollOffset) scrollOffset = itemTop;
        else if (itemTop + ROW_H > scrollOffset + viewH) scrollOffset = itemTop + ROW_H - viewH;
        clampScroll();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom) {
            scrollOffset -= (int)(delta * (ROW_H / 2.0));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, filtered.size() * ROW_H - (listBottom - listTop));
        scrollOffset  = Mth.clamp(scrollOffset, 0, maxScroll);
    }
}
