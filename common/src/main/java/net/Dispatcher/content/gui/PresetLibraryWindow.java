package net.Dispatcher.content.gui;

import de.mrjulsen.mcdragonlib.client.gui.events.DLGuiStandardEvents;
import de.mrjulsen.mcdragonlib.client.gui.widgets.base.DLWindow;
import de.mrjulsen.mcdragonlib.client.gui.widgets.base.DLWindowManager;
import de.mrjulsen.mcdragonlib.client.gui.widgets.components.DLButton;
import de.mrjulsen.mcdragonlib.client.gui.widgets.components.DLEditableLabel;
import de.mrjulsen.mcdragonlib.client.gui.widgets.components.DLRichTextEditBox;
import de.mrjulsen.mcdragonlib.client.gui.widgets.components.DLRichTextLabel;
import net.Dispatcher.DNetworking;
import net.Dispatcher.content.trains.schedule.AdvancedScheduleScreen;
import net.Dispatcher.foundation.network.PresetDownloadPacket;
import net.Dispatcher.foundation.network.PresetListPacket;
import net.Dispatcher.foundation.network.PresetUploadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game preset library (SPEC-WEB W4): save the edited schedule into the
 * server-side library, or load a stored preset onto the held item. Loading
 * closes the editor without saving (the item is rewritten server-side; saving
 * on close would clobber it with the still-open client copy).
 *
 * <p>The list is searched, not browsed: it opens filtered to the player's own
 * name, which is also the folder {@code PresetUploadPacket} files their saves
 * into, so a player sees their own presets first and clears the box to see the
 * rest of the server's.
 */
public class PresetLibraryWindow extends DLWindow {

    private static final int WIDTH = 280;
    private static final int HEIGHT = 236;
    private static final int ROWS = 6;
    private static final int ROW_HEIGHT = 26;
    private static final int FIRST_ROW_Y = 50;

    private final AdvancedScheduleScreen host;
    /** The full server listing; null while the first one is in flight. */
    private List<PresetListPacket.Row> rows;
    /** {@link #rows} narrowed by the search box — what the pager actually walks. */
    private List<PresetListPacket.Row> matches;
    private int page;

    private final DLRichTextLabel[] nameLabels = new DLRichTextLabel[ROWS];
    private final DLRichTextLabel[] metaLabels = new DLRichTextLabel[ROWS];
    private final DLButton[] loadButtons = new DLButton[ROWS];
    private final DLRichTextLabel statusLabel;
    private final DLRichTextLabel pageLabel;
    private final DLButton previousButton;
    private final DLButton nextButton;
    private final DLEditableLabel nameField;
    private final DLRichTextEditBox searchField;

    public PresetLibraryWindow(DLWindowManager manager, AdvancedScheduleScreen host) {
        super(manager);
        this.host = host;
        setSize(WIDTH, HEIGHT);
        var mcWindow = Minecraft.getInstance().getWindow();
        setPosition(Math.max(0, (mcWindow.getGuiScaledWidth() - WIDTH) / 2),
                Math.max(0, (mcWindow.getGuiScaledHeight() - HEIGHT) / 2));

        DLRichTextLabel title = addComponent(new DLRichTextLabel(15, 8, 100, 14));
        title.text.get().set(Component.translatable("dispatcher.gui.presets.title").getString());

        // Search shares the title row so the six visible rows survive. Its text and
        // change listener are wired at the end of the constructor — the listener
        // calls refresh(), which needs every row widget to exist already.
        searchField = addComponent(new DLRichTextEditBox(120, 5, WIDTH - 135, 18));
        searchField.maxCharacters.set(64);
        searchField.placeholderText.set(Component.translatable("dispatcher.gui.presets.search"));

        // Save row: click-to-edit name, then Save uploads the held item's schedule.
        nameField = addComponent(new DLEditableLabel(15, 26, 180, 16));
        nameField.text.set(Component.translatable("dispatcher.gui.presets.name_placeholder").getString());
        DLButton saveButton = addComponent(new DLButton(202, 25, 60, 18));
        saveButton.text.set(Component.translatable("dispatcher.gui.presets.save"));
        saveButton.addEventListener(DLGuiStandardEvents.ClickEvent.class, (source, event) -> {
            DNetworking.sendToServer(new PresetUploadPacket(nameField.text.get()));
            return false;
        });

        statusLabel = addComponent(new DLRichTextLabel(15, FIRST_ROW_Y + 4, WIDTH - 30, 14));
        statusLabel.text.get().set(Component.translatable("dispatcher.gui.presets.loading").getString());

        for (int i = 0; i < ROWS; i++) {
            int y = FIRST_ROW_Y + i * ROW_HEIGHT;
            nameLabels[i] = addComponent(new DLRichTextLabel(15, y, 190, 12));
            metaLabels[i] = addComponent(new DLRichTextLabel(15, y + 11, 190, 11));
            DLButton load = addComponent(new DLButton(WIDTH - 68, y + 2, 50, 18));
            load.text.set(Component.translatable("dispatcher.gui.presets.load"));
            int index = i;
            load.addEventListener(DLGuiStandardEvents.ClickEvent.class, (source, event) -> {
                loadClicked(index);
                return false;
            });
            loadButtons[i] = load;
        }

        int pagerY = HEIGHT - 24;
        previousButton = addComponent(new DLButton(15, pagerY, 20, 16));
        previousButton.text.set(Component.literal("<"));
        previousButton.addEventListener(DLGuiStandardEvents.ClickEvent.class, (source, event) -> {
            page = Math.max(0, page - 1);
            refresh();
            return false;
        });
        nextButton = addComponent(new DLButton(115, pagerY, 20, 16));
        nextButton.text.set(Component.literal(">"));
        nextButton.addEventListener(DLGuiStandardEvents.ClickEvent.class, (source, event) -> {
            page = Math.min(pageCount() - 1, page + 1);
            refresh();
            return false;
        });
        pageLabel = addComponent(new DLRichTextLabel(43, pagerY + 2, 66, 14));

        DLButton closeButton = addComponent(new DLButton(WIDTH - 75, pagerY, 60, 16));
        closeButton.text.set(Component.translatable("gui.done"));
        closeButton.addEventListener(DLGuiStandardEvents.ClickEvent.class, (source, event) -> {
            closeWindow();
            return false;
        });

        searchField.text.get().set(ownName());
        searchField.addEventListener(DLRichTextLabel.TextChangedEvent.class, (source, event) -> {
            page = 0;
            refresh();
            return false;
        });

        refresh();
    }

    /** Fresh listing from the server (initial load or post-upload refresh). */
    public void setRows(List<PresetListPacket.Row> rows) {
        this.rows = rows;
        refresh();
    }

    /** The player's own name — the default query, and the folder their saves land in. */
    private static String ownName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().getName();
    }

    /**
     * Narrows {@link #rows} to the search query — a case-insensitive substring of
     * either the display name or the source.
     *
     * <p>Matching the source as well is what makes the default query work: the
     * server folds the folder into the name and head-truncates it at 64 chars
     * (see {@code PresetListRequestPacket}), so a long folder/name pair can lose
     * the very folder being searched for. {@code source} ("item:&lt;player&gt;")
     * is never truncated, so a player's own saves stay findable either way.
     */
    private void applyFilter() {
        if (rows == null) {
            matches = null;
            return;
        }
        String query = searchField.text.get().getPlainText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            matches = rows;
            return;
        }
        List<PresetListPacket.Row> found = new ArrayList<>();
        for (PresetListPacket.Row row : rows)
            if (row.name().toLowerCase(Locale.ROOT).contains(query)
                    || row.source().toLowerCase(Locale.ROOT).contains(query))
                found.add(row);
        matches = found;
    }

    private void loadClicked(int index) {
        if (matches == null)
            return;
        int rowIndex = page * ROWS + index;
        if (rowIndex >= matches.size())
            return;
        PresetListPacket.Row row = matches.get(rowIndex);
        // The item is rewritten server-side; the editor's save-on-close would
        // overwrite it with the stale client copy — skip it. closeWindow()
        // restores the schedule screen (openWindow replaced it with the
        // DLScreen wrapper), closeContainer() then closes screen AND menu
        // properly — its removed() honors the discard flag.
        host.dispatcher$discardOnClose();
        closeWindow();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.closeContainer();
        else
            mc.setScreen(null);
        DNetworking.sendToServer(new PresetDownloadPacket(row.id()));
    }

    private int pageCount() {
        return matches == null ? 1 : Math.max(1, (matches.size() + ROWS - 1) / ROWS);
    }

    private void refresh() {
        applyFilter();
        if (page >= pageCount())
            page = Math.max(0, pageCount() - 1);

        boolean loading = matches == null;
        boolean empty = !loading && matches.isEmpty();
        statusLabel.visible.set(loading || empty);
        if (loading || empty) {
            // An empty library and an empty result set read alike but need different
            // advice — one says "save one", the other says "clear the search".
            String key = loading ? "dispatcher.gui.presets.loading"
                    : rows.isEmpty() ? "dispatcher.gui.presets.empty"
                    : "dispatcher.gui.presets.no_match";
            statusLabel.text.get().set(Component.translatable(key).getString());
        }

        for (int i = 0; i < ROWS; i++) {
            int rowIndex = page * ROWS + i;
            boolean shown = !loading && rowIndex < matches.size();
            nameLabels[i].visible.set(shown);
            metaLabels[i].visible.set(shown);
            loadButtons[i].visible.set(shown);
            if (!shown)
                continue;
            PresetListPacket.Row row = matches.get(rowIndex);
            nameLabels[i].text.get().set(row.name());
            metaLabels[i].text.get().set(row.source() + " · " + row.entries() + " · " + age(row.updatedMs()));
        }

        boolean pager = pageCount() > 1;
        previousButton.visible.set(pager);
        nextButton.visible.set(pager);
        pageLabel.visible.set(pager);
        previousButton.enabled.set(page > 0);
        nextButton.enabled.set(page < pageCount() - 1);
        if (pager)
            pageLabel.text.get().set((page + 1) + " / " + pageCount());
    }

    private static String age(long updatedMs) {
        long minutes = Math.max(0, (System.currentTimeMillis() - updatedMs) / 60_000);
        if (minutes < 60)
            return minutes + "m";
        long hours = minutes / 60;
        if (hours < 48)
            return hours + "h";
        return (hours / 24) + "d";
    }
}
