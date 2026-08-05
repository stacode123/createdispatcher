package net.Dispatcher.web.deploy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import net.Dispatcher.Interfaces.IAdvancedScheduleRuntime;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.content.simulator.ScheduleCompiler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies planner assignments to real trains — the one web action that changes the running
 * world. Everything here runs on the SERVER THREAD (the caller marshals); every attempt,
 * including every refusal, lands in the {@link AuditLog}.
 *
 * <p>The install order is load-bearing and was verified against Create 6.0.8 plus this mod's
 * {@code ScheduleRuntimeMixin}:
 * <ol>
 *   <li>strip {@code "Progress"} from the tag — {@code setSchedule} clamps {@code currentEntry}
 *       to {@code schedule.savedProgress}, so a preset captured mid-trip would resume there;</li>
 *   <li>{@code runtime.discardSchedule()} FIRST — {@code setSchedule} alone does not cancel
 *       navigation, it only resets the runtime;</li>
 *   <li>guard a non-empty schedule — an empty one strands the train with no destination;</li>
 *   <li>{@code runtime.setSchedule(schedule, false)};</li>
 *   <li>{@code setAdvancedSchedule(true)} AFTER — the mixin resets that flag at the
 *       {@code setSchedule} TAIL, so setting it earlier is silently undone. It makes the
 *       conductor hand back an Advanced Schedule item, which is what web presets are.</li>
 * </ol>
 */
public final class DeployService {

    /** How much the deploy is allowed to interrupt. */
    public enum Mode {
        /** Swap the schedule whatever the train is doing; a moving train reroutes. */
        IMMEDIATE,
        /** Only trains standing still with nowhere to go — the safe default. */
        IDLE_ONLY;

        public static Mode parse(String value) {
            return "immediate".equalsIgnoreCase(value) ? IMMEDIATE : IDLE_ONLY;
        }
    }

    /** One materialized assignment: the train, and the (edited) preset schedule to install. */
    public record Assignment(UUID trainId, String presetId, String presetName, CompoundTag scheduleTag) {}

    /**
     * @param reason  refusal key when {@code !ok}: {@code not_found}, {@code derailed},
     *                {@code not_idle}, {@code preset_invalid}, {@code empty}, {@code error}
     * @param notices simulator-compile complaints; the deploy still went through, the
     *                planner's projections just will not model those instructions
     */
    public record Result(UUID trainId, String trainName, boolean ok, String reason,
                         List<String> notices) {}

    private DeployService() {}

    /** SERVER THREAD. Returns one result per assignment, in request order. */
    public static List<Result> deploy(MinecraftServer server, Mode mode, String user,
                                      String discordId, List<Assignment> assignments) {
        AuditLog audit = AuditLog.of(server);
        long gameTick = server.overworld().getGameTime();
        List<Result> results = new ArrayList<>(assignments.size());
        for (Assignment assignment : assignments) {
            Result result;
            try {
                result = apply(server, mode, assignment);
            } catch (Throwable t) {
                DispatcherMod.LOGGER.error("Dispatcher web: deploy to {} failed", assignment.trainId(), t);
                result = new Result(assignment.trainId(), "", false, "error", List.of());
            }
            results.add(result);
            audit.append(auditEntry(gameTick, mode, user, discordId, assignment, result));
        }
        long applied = results.stream().filter(Result::ok).count();
        DispatcherMod.LOGGER.info("Dispatcher web: {} deployed {} of {} schedule(s) in {} mode",
                user, applied, results.size(), mode.name().toLowerCase());
        return results;
    }

    private static Result apply(MinecraftServer server, Mode mode, Assignment assignment) {
        Train train = Create.RAILWAYS.sided(server.overworld()).trains.get(assignment.trainId());
        if (train == null)
            return new Result(assignment.trainId(), "", false, "not_found", List.of());
        String name = train.name.getString();
        if (train.derailed)
            return new Result(assignment.trainId(), name, false, "derailed", List.of());
        if (mode == Mode.IDLE_ONLY && !isIdle(train))
            return new Result(assignment.trainId(), name, false, "not_idle", List.of());

        // (1) fresh progress: the preset must start at entry 0, not where its source train was
        CompoundTag tag = assignment.scheduleTag().copy();
        tag.remove("Progress");
        Schedule schedule;
        try {
            schedule = Schedule.fromTag(tag);
        } catch (Exception e) {
            return new Result(assignment.trainId(), name, false, "preset_invalid", List.of());
        }
        // (3) an empty schedule would leave the train parked with no way back
        if (schedule.entries.isEmpty())
            return new Result(assignment.trainId(), name, false, "empty", List.of());

        // The simulator's opinion is advisory: a schedule it cannot model still drives a
        // train perfectly well in-game. Report it, deploy anyway.
        List<String> notices = new ArrayList<>();
        try {
            ScheduleCompiler.CompileResult compiled = ScheduleCompiler.compile(schedule);
            if (!compiled.clean())
                for (ScheduleCompiler.Problem problem : compiled.problems())
                    notices.add(problem.detail() == null || problem.detail().isBlank()
                            ? problem.translationKey()
                            : problem.translationKey() + ": " + problem.detail());
        } catch (Throwable t) {
            notices.add("dispatcher.sim.refuse.compile_failed");
        }

        // (2) cancel navigation, (4) install, (5) then flag as advanced — order matters
        train.runtime.discardSchedule();
        train.runtime.setSchedule(schedule, false);
        ((IAdvancedScheduleRuntime) train.runtime).setAdvancedSchedule(true);
        return new Result(assignment.trainId(), name, true, "", List.copyOf(notices));
    }

    /**
     * Standing still, nothing left to reach, and not mid-trip: the runtime is paused,
     * finished, schedule-less, or dwelling at a station. Mirrors the "safe" promise the
     * deploy dialog makes — an IDLE_ONLY deploy never interrupts a journey.
     */
    private static boolean isIdle(Train train) {
        if (Math.abs(train.speed) > 0.01) return false;
        if (train.navigation != null && train.navigation.destination != null) return false;
        return train.runtime.getSchedule() == null || train.runtime.paused
                || train.runtime.completed || train.getCurrentStation() != null;
    }

    private static JsonObject auditEntry(long gameTick, Mode mode, String user, String discordId,
                                         Assignment assignment, Result result) {
        JsonObject entry = new JsonObject();
        entry.addProperty("gameTick", gameTick);
        entry.addProperty("user", user);
        entry.addProperty("discordId", discordId);
        entry.addProperty("mode", mode.name());
        entry.addProperty("trainId", assignment.trainId().toString());
        entry.addProperty("train", result.trainName());
        entry.addProperty("presetId", assignment.presetId());
        entry.addProperty("preset", assignment.presetName());
        entry.addProperty("ok", result.ok());
        if (!result.ok()) entry.addProperty("reason", result.reason());
        if (!result.notices().isEmpty()) {
            JsonArray notices = new JsonArray();
            for (String notice : result.notices()) notices.add(notice);
            entry.add("notices", notices);
        }
        return entry;
    }

    /** The wire shape of a deploy response: per-train rows plus the applied/skipped tally. */
    public static JsonObject resultsJson(Mode mode, List<Result> results) {
        JsonArray rows = new JsonArray();
        int applied = 0;
        for (Result result : results) {
            if (result.ok()) applied++;
            JsonObject row = new JsonObject();
            row.addProperty("trainId", result.trainId().toString());
            row.addProperty("train", result.trainName());
            row.addProperty("ok", result.ok());
            if (!result.ok()) row.addProperty("reason", result.reason());
            if (!result.notices().isEmpty()) {
                JsonArray notices = new JsonArray();
                for (String notice : result.notices()) notices.add(notice);
                row.add("notices", notices);
            }
            rows.add(row);
        }
        JsonObject body = new JsonObject();
        body.addProperty("mode", mode.name());
        body.addProperty("applied", applied);
        body.addProperty("skipped", results.size() - applied);
        body.add("results", rows);
        return body;
    }
}
