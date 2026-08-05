package net.Dispatcher.Interfaces;

/**
 * Marks a {@code ScheduleRuntime} as driving an Advanced Schedule.
 *
 * <p>Create's {@code ScheduleRuntime.returnSchedule()} always fabricates a vanilla schedule item,
 * so a conductor would otherwise hand back the wrong item. {@code ScheduleRuntimeMixin} carries
 * this flag through NBT and swaps the returned stack when it is set.
 */
public interface IAdvancedScheduleRuntime {
    boolean isAdvancedSchedule();

    void setAdvancedSchedule(boolean advancedSchedule);
}
