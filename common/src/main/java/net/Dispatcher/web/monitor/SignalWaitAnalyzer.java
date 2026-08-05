package net.Dispatcher.web.monitor;

import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.graph.WebGraphStore;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Raises SIGNAL_WAIT when a train has been held at a red signal past the alert threshold —
 * Create already maintains {@code Navigation.ticksWaitingForSignal}, so this is a threshold read.
 * WARN at the threshold, CRITICAL at 4x, resolved as soon as the wait ends. Server thread.
 */
public final class SignalWaitAnalyzer {

    public void analyze(MinecraftServer server, WebGraphStore store, NotificationHub hub, long gameTick) {
        long warnTicks = DispatcherConfig.COMMON.WebSignalWaitAlertSeconds.get() * 20L;
        if (warnTicks <= 0) return;
        Set<String> stillWaiting = new HashSet<>();

        for (Train train : Create.RAILWAYS.sided(server.overworld()).trains.values()) {
            if (train.graph == null || train.navigation == null || train.carriages.isEmpty()) continue;
            int waiting = train.navigation.ticksWaitingForSignal;
            if (waiting <= warnTicks) continue;
            MonitorPositions.Pos pos = MonitorPositions.locate(store, train);
            if (pos == null) continue;
            String id = "wait:" + train.id;
            stillWaiting.add(id);
            WebNotification.Severity severity = waiting > warnTicks * 4
                    ? WebNotification.Severity.CRITICAL
                    : WebNotification.Severity.WARN;
            JsonObject data = new JsonObject();
            data.addProperty("waitTicks", waiting);
            hub.raiseOrUpdate(new WebNotification(id, WebNotification.Kind.SIGNAL_WAIT, severity,
                    train.name.getString() + " has been waiting at a signal for "
                            + WebNotification.formatTicks(waiting),
                    List.of(new WebNotification.TrainRef(train.id, train.name.getString())),
                    pos.graphId(), pos.x(), pos.z(), pos.dim(),
                    gameTick - waiting, gameTick, null, data));
        }

        for (WebNotification notification : hub.active())
            if (notification.kind() == WebNotification.Kind.SIGNAL_WAIT
                    && !stillWaiting.contains(notification.id()))
                hub.resolve(notification.id(), gameTick);
    }
}
