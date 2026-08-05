package net.Dispatcher.content.trains.schedule;

import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleMenu;
import com.simibubi.create.content.trains.schedule.ScheduleScreen;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import de.mrjulsen.mcdragonlib.client.gui.widgets.base.DLWindow;
import net.Dispatcher.DNetworking;
import net.Dispatcher.content.gui.SimTimeFormat;
import net.Dispatcher.content.gui.SimulationClientData;
import net.Dispatcher.content.gui.SimulationResultsWindow;
import net.Dispatcher.content.gui.SimulationSetupWindow;
import net.Dispatcher.content.simulator.SimulationPayload;
import net.Dispatcher.foundation.network.AdvancedScheduleSavePacket;
import net.Dispatcher.mixin.mixinaccesors.ScheduleScreenAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create's schedule editor, saving through our own packet. Subclassing the
 * real {@link ScheduleScreen} keeps every Create feature and lets Railways
 * Navigator's {@code instanceof ScheduleScreen}-gated mixins apply here too.
 * Adds the Simulate button opening the phantom setup window (SPEC §4.1).
 */
public class AdvancedScheduleScreen extends ScheduleScreen {

    // Create's card layout constants (ScheduleScreen.renderScheduleEntry).
    private static final int CARD_WIDTH = 195;
    private static final int CARD_HEADER = 22;

    private Button simulateButton;
    private boolean simulating;
    /** Set when a preset load rewrites the item server-side — skip save-on-close. */
    private boolean discardOnClose;
    /** Content hash of the shown schedule, refreshed every second. */
    private int scheduleHash;
    private int hashCountdown;

    public AdvancedScheduleScreen(ScheduleMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Override
    protected void init() {
        super.init();
        // Bottom bar, next to Create's "Skip current stop" (icons at +45/+63).
        simulateButton = Button.builder(simulateLabel(),
                        button -> DLWindow.openWindow(manager -> new SimulationSetupWindow(manager, this)))
                .bounds(leftPos + 85, topPos + 196, 60, 18)
                .build();
        simulateButton.active = !simulating;
        addRenderableWidget(simulateButton);
        addRenderableWidget(Button.builder(Component.translatable("dispatcher.gui.presets.button"),
                        button -> net.Dispatcher.content.gui.PresetLibraryOpener.request(this))
                .bounds(leftPos + 148, topPos + 196, 60, 18)
                .build());
    }

    /** Called by the preset library before it loads a preset onto the item. */
    public void dispatcher$discardOnClose() {
        discardOnClose = true;
    }

    private Component simulateLabel() {
        return Component.translatable(simulating
                ? "dispatcher.gui.simulate.running"
                : "dispatcher.gui.simulate.button");
    }

    public void simulationRequested() {
        simulating = true;
        if (simulateButton != null) {
            simulateButton.setMessage(simulateLabel());
            simulateButton.active = false;
        }
    }

    public void simulationArrived(SimulationPayload payload) {
        simulating = false;
        if (simulateButton != null) {
            simulateButton.setMessage(simulateLabel());
            simulateButton.active = true;
        }
        DLWindow.openWindow(manager -> new SimulationResultsWindow(manager, payload));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (--hashCountdown <= 0) {
            hashCountdown = 20;
            Schedule schedule = ((ScheduleScreenAccessor) this).dispatcher$getSchedule();
            scheduleHash = schedule == null ? 0 : SimulationClientData.hash(schedule);
        }
    }

    /**
     * Simulated arrival/departure times on each entry card (SPEC §4.1),
     * drawn over Create's rendering. Greyed out once the schedule no longer
     * matches the content the simulation ran on.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        renderSimulatedTimes(graphics);
    }

    private void renderSimulatedTimes(GuiGraphics graphics) {
        SimulationPayload payload = SimulationClientData.lastResults;
        if (payload == null || payload.refused())
            return;
        ScheduleScreenAccessor accessor = (ScheduleScreenAccessor) this;
        // The condition/destination editor covers the cards with a gradient.
        if (accessor.dispatcher$getEditingCondition() != null
                || accessor.dispatcher$getEditingDestination() != null)
            return;
        Schedule schedule = accessor.dispatcher$getSchedule();
        if (schedule == null || schedule.entries.isEmpty())
            return;
        SimulationPayload.TrainLine phantom = payload.trains.stream()
                .filter(SimulationPayload.TrainLine::phantom).findFirst().orElse(null);
        if (phantom == null)
            return;
        Map<Integer, SimulationPayload.Visit> firstVisits = new HashMap<>();
        for (SimulationPayload.Visit visit : phantom.visits())
            firstVisits.putIfAbsent(visit.entryIndex(), visit);
        if (firstVisits.isEmpty())
            return;

        boolean stale = scheduleHash != SimulationClientData.lastScheduleHash;
        int color = stale ? 0xFF8A8A8A : 0xFFB8E8FF;
        float scrollOffset = -accessor.dispatcher$getScroll().getValue(minecraft.getFrameTime());

        // Clip to the schedule strip area, like Create's own stencil.
        graphics.enableScissor(leftPos + 16, topPos + 16, leftPos + 236, topPos + 189);
        int yOffset = 25;
        List<ScheduleEntry> entries = schedule.entries;
        for (int i = 0; i < entries.size(); i++) {
            ScheduleEntry entry = entries.get(i);
            int maxRows = 0;
            for (List<ScheduleWaitCondition> column : entry.conditions)
                maxRows = Math.max(maxRows, column.size());
            boolean supportsConditions = entry.instruction != null
                    && entry.instruction.supportsConditions();
            int cardHeight = CARD_HEADER + (supportsConditions ? 24 + maxRows * 18 : 4);

            SimulationPayload.Visit visit = firstVisits.get(i);
            if (visit != null) {
                String text = SimTimeFormat.time(payload, visit.arrivalTick());
                if (visit.departureTick() >= 0 && visit.departureTick() != visit.arrivalTick())
                    text += " > " + SimTimeFormat.time(payload, visit.departureTick());
                int textWidth = font.width(text);
                int x = leftPos + 25 + CARD_WIDTH - 17 - textWidth;
                int y = Math.round(topPos + yOffset + scrollOffset) + 7;
                graphics.fill(x - 2, y - 2, x + textWidth + 2, y + 9, 0xA0101018);
                graphics.drawString(font, text, x, y, color, false);
            }

            yOffset += cardHeight;
            if (i + 1 < entries.size())
                yOffset += 10;
        }
        graphics.disableScissor();
    }

    @Override
    public void removed() {
        // The vanilla ScheduleEditPacket sent by super is discarded server-side:
        // its handler requires Create's own schedule item in the main hand.
        super.removed();
        if (discardOnClose)
            return;
        Schedule schedule = ((ScheduleScreenAccessor) this).dispatcher$getSchedule();
        if (schedule != null)
            DNetworking.sendToServer(new AdvancedScheduleSavePacket(schedule));
    }
}
