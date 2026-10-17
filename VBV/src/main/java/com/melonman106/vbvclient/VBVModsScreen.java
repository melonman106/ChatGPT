package com.melonman106.vbvclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Native Eagler mod browser laid out like Mod Menu 26.2: title and search box over a
 * left-hand mod list, selected-mod details (icon, name, badges, version, authors,
 * description) on the right, and Done at the bottom.
 *
 * Everything is drawn with fill/text so it needs no extra textures, and rows are
 * scrolled a whole row at a time so no scissor clipping is required.
 */
public final class VBVModsScreen extends Screen {
    private static final int RIGHT_PANE_Y = 48;
    private static final int LIST_TOP = 67;          // 48 + 19, same as Mod Menu's non-config layout
    private static final int ROW_HEIGHT = 36;
    private static final int ICON = 32;
    private static final int DESC_TOP = 84;

    private final Screen parent;
    private final List<VBVModInfo> all = new ArrayList<>();
    private final List<VBVModInfo> shown = new ArrayList<>();

    private VBVModInfo selected;
    private String searchText = "";
    private boolean ascending = true;
    private int typeFilter = 0;                       // 0 = all, 1 = native, 2 = resource packs
    private boolean filtersShown = false;
    private int listScroll = 0;
    private int descScroll = 0;

    private final List<String> descLines = new ArrayList<>();
    private final List<Integer> descColors = new ArrayList<>();

    private EditBox searchBox;
    private Button sortButton;
    private Button typeButton;
    private Button configButton;

    private int paneWidth;
    private int rightPaneX;
    private int searchBoxX;
    private int filtersX;

    public VBVModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
        this.all.addAll(VBVClient.getMods());
    }

    // ------------------------------------------------------------------ layout

    private int listBottom() {
        return height - 36;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - LIST_TOP - 4) / ROW_HEIGHT);
    }

    private int maxListScroll() {
        return Math.max(0, shown.size() - visibleRows());
    }

    private boolean listOverflows() {
        return shown.size() > visibleRows();
    }

    private int rowWidth() {
        return paneWidth - (listOverflows() ? 18 : 12);
    }

    private int descVisibleLines() {
        return Math.max(1, (listBottom() - DESC_TOP - 6) / (font.lineHeight + 1));
    }

    @Override
    protected void init() {
        super.init();
        paneWidth = width / 2 - 8;
        rightPaneX = width - paneWidth;

        int searchWidth = paneWidth - 32 - 22;
        searchBoxX = paneWidth / 2 - searchWidth / 2 - 11;
        searchBox = new EditBox(font, searchBoxX, 22, searchWidth, 20, Component.literal("Search"));
        searchBox.setValue(searchText);
        searchBox.setResponder(text -> {
            searchText = text;
            listScroll = 0;
            refilter();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("="), button -> {
            filtersShown = !filtersShown;
            applyFilterVisibility();
        }).bounds(paneWidth / 2 + searchWidth / 2 - 10 + 2, 22, 20, 20).build());

        int sortWidth = font.width("Sort: Z-A") + 28;
        int typeWidth = font.width("Show: Packs") + 20;
        int filtersWidth = sortWidth + typeWidth + 2;
        int searchRowWidth = searchBoxX + searchWidth + 22;
        filtersX = searchRowWidth - filtersWidth + 1;

        sortButton = Button.builder(Component.literal(sortLabel()), button -> {
            ascending = !ascending;
            button.setMessage(Component.literal(sortLabel()));
            refilter();
        }).bounds(filtersX, 45, sortWidth, 20).build();
        typeButton = Button.builder(Component.literal(typeLabel()), button -> {
            typeFilter = (typeFilter + 1) % 3;
            button.setMessage(Component.literal(typeLabel()));
            listScroll = 0;
            refilter();
        }).bounds(filtersX + sortWidth + 2, 45, typeWidth, 20).build();
        addRenderableWidget(sortButton);
        addRenderableWidget(typeButton);

        configButton = Button.builder(Component.literal("Config"), button -> {
            if (selected != null && "simple-hud-enhanced".equals(selected.id())) {
                VBVNav.open(new VBVSimpleHudConfigScreen(this));
            }
        }).bounds(width - 54, RIGHT_PANE_Y, 50, 20).build();
        addRenderableWidget(configButton);

        addRenderableWidget(Button.builder(Component.literal("World Map"),
            button -> VBVNav.open(new VBVWorldMapScreen(this)))
            .bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> VBVNav.open(parent))
            .bounds(width / 2 + 4, height - 28, 150, 20).build());

        applyFilterVisibility();
        refilter();
    }

    private void applyFilterVisibility() {
        sortButton.visible = filtersShown;
        typeButton.visible = filtersShown;
    }

    private String sortLabel() {
        return ascending ? "Sort: A-Z" : "Sort: Z-A";
    }

    private String typeLabel() {
        return typeFilter == 0 ? "Show: All" : typeFilter == 1 ? "Show: Native" : "Show: Packs";
    }

    // ------------------------------------------------------------------ data

    private void refilter() {
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (VBVModInfo info : all) {
            if (typeFilter == 1 && info.resourcePackMod()) continue;
            if (typeFilter == 2 && !info.resourcePackMod()) continue;
            if (!query.isEmpty() && !matches(info, query)) continue;
            shown.add(info);
        }
        Comparator<VBVModInfo> byName = Comparator.comparing(VBVModInfo::name, String.CASE_INSENSITIVE_ORDER);
        shown.sort(ascending ? byName : byName.reversed());

        if (selected == null || !shown.contains(selected)) {
            select(shown.isEmpty() ? null : shown.get(0));
        }
        listScroll = Math.max(0, Math.min(listScroll, maxListScroll()));
    }

    private static boolean matches(VBVModInfo info, String query) {
        return String.valueOf(info.name()).toLowerCase(Locale.ROOT).contains(query)
            || String.valueOf(info.id()).toLowerCase(Locale.ROOT).contains(query)
            || String.valueOf(info.authors()).toLowerCase(Locale.ROOT).contains(query)
            || String.valueOf(info.description()).toLowerCase(Locale.ROOT).contains(query);
    }

    private void select(VBVModInfo info) {
        selected = info;
        descScroll = 0;
        if (configButton != null) {
            configButton.visible = info != null && "simple-hud-enhanced".equals(info.id());
        }
        rebuildDescription();
    }

    private void rebuildDescription() {
        descLines.clear();
        descColors.clear();
        if (selected == null || font == null) return;
        int wrapWidth = paneWidth - 6 - 18;
        addWrapped(String.valueOf(selected.description()), wrapWidth, 0xFFAAAAAA);
        addLine("", 0xFFAAAAAA);
        addLine("Type", 0xFFFFFFFF);
        addWrapped(selected.resourcePackMod() ? "Resource-pack client mod" : "Native client mod", wrapWidth, 0xFFAAAAAA);
        addLine("", 0xFFAAAAAA);
        addLine("Mod ID", 0xFFFFFFFF);
        addWrapped(String.valueOf(selected.id()), wrapWidth, 0xFFAAAAAA);
    }

    private void addLine(String text, int color) {
        descLines.add(text);
        descColors.add(color);
    }

    private void addWrapped(String text, int width, int color) {
        for (String line : wrap(text, width, Integer.MAX_VALUE)) addLine(line, color);
    }

    /** Greedy word wrap using the font; long words are hard-split. */
    private List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        StringBuilder current = new StringBuilder();
        for (String word : text.replace("\n", " ").split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            String rest = word;
            while (font.width(rest) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && font.width(rest.substring(0, cut)) > maxWidth) cut--;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            current.append(rest);
        }
        if (current.length() > 0) lines.add(current.toString());
        if (lines.size() > maxLines) {
            List<String> trimmed = new ArrayList<>(lines.subList(0, maxLines));
            trimmed.set(maxLines - 1, ellipsize(trimmed.get(maxLines - 1) + "...", maxWidth));
            return trimmed;
        }
        return lines;
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String body = text.endsWith("...") ? text.substring(0, text.length() - 3) : text;
        while (!body.isEmpty() && font.width(body + "...") > maxWidth) body = body.substring(0, body.length() - 1);
        return body + "...";
    }

    // ------------------------------------------------------------------ input

    private void moveSelection(int delta) {
        if (shown.isEmpty()) return;
        int index = selected == null ? 0 : Math.max(0, shown.indexOf(selected));
        index = Math.max(0, Math.min(shown.size() - 1, index + delta));
        select(shown.get(index));
        if (index < listScroll) listScroll = index;
        if (index >= listScroll + visibleRows()) listScroll = index - visibleRows() + 1;
        listScroll = Math.max(0, Math.min(listScroll, maxListScroll()));
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.isUp()) {
            moveSelection(-1);
            return true;
        }
        if (input.isDown()) {
            moveSelection(1);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        double mx = click.x();
        double my = click.y();
        int rowLeft = 6;
        if (mx >= rowLeft && mx <= rowLeft + rowWidth() && my >= LIST_TOP && my < listBottom()) {
            for (int k = 0; k < visibleRows(); k++) {
                int index = listScroll + k;
                if (index >= shown.size()) break;
                int top = LIST_TOP + 4 + k * ROW_HEIGHT - 2;
                if (my >= top && my < top + ROW_HEIGHT) {
                    select(shown.get(index));
                    return true;
                }
            }
        }
        return false;
    }

    // Deliberately no @Override: if the 26.2 signature differs this simply stays unused.
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (step == 0) return false;
        if (mouseX < paneWidth) {
            listScroll = Math.max(0, Math.min(maxListScroll(), listScroll + step));
        } else {
            int max = Math.max(0, descLines.size() - descVisibleLines());
            descScroll = Math.max(0, Math.min(max, descScroll + step));
        }
        return true;
    }

    @Override
    public void onClose() {
        VBVNav.open(parent);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Title centred over the list, like Mod Menu.
        String title = "Mods";
        graphics.text(font, title, paneWidth / 2 - font.width(title) / 2, 8, 0xFFFFFFFF, true);
        drawCount(graphics);

        drawPanel(graphics, 0, paneWidth, LIST_TOP, listBottom());
        drawList(graphics);

        drawPanel(graphics, rightPaneX, width, DESC_TOP, listBottom());
        drawDetails(graphics);
        drawDescription(graphics);
    }

    private void drawCount(GuiGraphicsExtractor graphics) {
        int total = all.size();
        String full = shown.size() == total
            ? "Showing " + total + " mods"
            : "Showing " + shown.size() + "/" + total + " mods";
        String text = full;
        if (filtersShown && searchBoxX + font.width(full) > filtersX - 5) {
            text = shown.size() + " mods";
        }
        graphics.text(font, text, searchBoxX, 52, 0xFFFFFFFF, true);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x1, int x2, int top, int bottom) {
        graphics.fill(x1, top, x2, bottom, 0x80000000);
        graphics.fill(x1, top - 2, x2, top - 1, 0xFF000000);   // header separator
        graphics.fill(x1, top - 1, x2, top, 0xFF373737);
        graphics.fill(x1, bottom, x2, bottom + 1, 0xFF373737);  // footer separator
        graphics.fill(x1, bottom + 1, x2, bottom + 2, 0xFF000000);
    }

    private void drawList(GuiGraphicsExtractor graphics) {
        int rowLeft = 6;
        int rowWidth = rowWidth();
        for (int k = 0; k < visibleRows(); k++) {
            int index = listScroll + k;
            if (index >= shown.size()) break;
            VBVModInfo info = shown.get(index);
            int y = LIST_TOP + 4 + k * ROW_HEIGHT;

            if (info == selected) {
                graphics.fill(rowLeft - 2, y - 2, rowLeft + rowWidth + 2, y + ICON + 2, 0xFFFFFFFF);
                graphics.fill(rowLeft - 1, y - 1, rowLeft + rowWidth + 1, y + ICON + 1, 0xFF000000);
            }

            drawIcon(graphics, info, rowLeft, y, ICON);

            int textX = rowLeft + ICON + 3;
            int maxNameWidth = rowWidth - ICON - 3;
            String name = ellipsize(String.valueOf(info.name()), maxNameWidth);
            graphics.text(font, name, textX, y + 1, 0xFFFFFFFF, true);
            drawBadges(graphics, info, textX + font.width(name) + 2, y, rowLeft + rowWidth);

            List<String> summary = wrap(String.valueOf(info.description()), rowWidth - ICON - 7, 2);
            for (int i = 0; i < summary.size(); i++) {
                graphics.text(font, summary.get(i), textX + 4, y + font.lineHeight + 2 + i * font.lineHeight, 0xFF808080, false);
            }
        }
        drawScrollbar(graphics, paneWidth - 6, LIST_TOP, listBottom(), shown.size(), visibleRows(), listScroll);
    }

    private void drawDetails(GuiGraphicsExtractor graphics) {
        if (selected == null) return;
        int x = rightPaneX;
        drawIcon(graphics, selected, x, RIGHT_PANE_Y, ICON);

        int imageOffset = 36;
        int maxNameWidth = width - (x + imageOffset) - (configButton != null && configButton.visible ? 58 : 4);
        String name = ellipsize(String.valueOf(selected.name()), maxNameWidth);
        graphics.text(font, name, x + imageOffset, RIGHT_PANE_Y + 1, 0xFFFFFFFF, true);
        drawBadges(graphics, selected, x + imageOffset + font.width(name) + 2, RIGHT_PANE_Y,
            width - (configButton != null && configButton.visible ? 58 : 28));

        int lineSpacing = font.lineHeight + 1;
        graphics.text(font, String.valueOf(selected.version()), x + imageOffset,
            RIGHT_PANE_Y + 2 + lineSpacing, 0xFFAAAAAA, true);
        List<String> authors = wrap("By " + selected.authors(), paneWidth - imageOffset - 4, 1);
        if (!authors.isEmpty()) {
            graphics.text(font, authors.get(0), x + imageOffset, RIGHT_PANE_Y + 2 + lineSpacing * 2, 0xFFAAAAAA, true);
        }
    }

    private void drawDescription(GuiGraphicsExtractor graphics) {
        int lineHeight = font.lineHeight + 1;
        int visible = descVisibleLines();
        for (int k = 0; k < visible; k++) {
            int index = descScroll + k;
            if (index >= descLines.size()) break;
            graphics.text(font, descLines.get(index), rightPaneX + 6, DESC_TOP + 4 + k * lineHeight,
                descColors.get(index), false);
        }
        drawScrollbar(graphics, width - 6, DESC_TOP, listBottom(), descLines.size(), visible, descScroll);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int bottom,
                               int total, int visible, int scroll) {
        if (total <= visible) return;
        int track = bottom - top;
        int thumb = Math.max(32, track * visible / total);
        int range = total - visible;
        int thumbY = top + (track - thumb) * scroll / range;
        graphics.fill(x, top, x + 6, bottom, 0xFF000000);
        graphics.fill(x, thumbY, x + 6, thumbY + thumb, 0xFF808080);
        graphics.fill(x, thumbY, x + 5, thumbY + thumb - 1, 0xFFC0C0C0);
    }

    private void drawIcon(GuiGraphicsExtractor graphics, VBVModInfo info, int x, int y, int size) {
        int h = String.valueOf(info.id()).hashCode();
        int r = 70 + (h & 0x7F);
        int g = 70 + ((h >> 7) & 0x7F);
        int b = 70 + ((h >> 14) & 0x7F);
        graphics.fill(x, y, x + size, y + size, 0xFF000000 | (r << 16) | (g << 8) | b);
        String name = String.valueOf(info.name());
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        graphics.text(font, letter, x + (size - font.width(letter)) / 2,
            y + (size - font.lineHeight) / 2 + 1, 0xFFFFFFFF, true);
    }

    private void drawBadges(GuiGraphicsExtractor graphics, VBVModInfo info, int startX, int y, int maxX) {
        int x = startX;
        x = drawBadge(graphics, x, y, maxX, "Client", 0xFF2B4B7C, 0xFF0E2A55);
        if (info.resourcePackMod()) {
            drawBadge(graphics, x, y, maxX, "Pack", 0xFF7A2B7C, 0xFF510D54);
        }
    }

    private int drawBadge(GuiGraphicsExtractor graphics, int x, int y, int maxX, String label, int outline, int fill) {
        int tagWidth = font.width(label) + 6;
        if (x + tagWidth >= maxX) return x;
        int lh = font.lineHeight;
        graphics.fill(x + 1, y - 1, x + tagWidth, y, outline);
        graphics.fill(x, y, x + 1, y + lh, outline);
        graphics.fill(x + 1, y + lh, x + tagWidth, y + lh + 1, outline);
        graphics.fill(x + tagWidth, y, x + tagWidth + 1, y + lh, outline);
        graphics.fill(x + 1, y, x + tagWidth, y + lh, fill);
        graphics.text(font, label, (int) (x + 1 + (tagWidth - font.width(label)) / 2.0F), y + 1, 0xFFCACACA, false);
        return x + tagWidth + 3;
    }
}