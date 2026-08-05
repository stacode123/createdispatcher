package net.Dispatcher.web.monitor;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deadlock detector's cycle finder: only true wait-for cycles come back, never chains. */
class TarjanSccTest {

    @Test
    void findsASimpleTwoCycle() {
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "A", List.of("B"),
                "B", List.of("A")));
        assertEquals(1, cycles.size());
        assertEquals(Set.of("A", "B"), new HashSet<>(cycles.get(0)));
    }

    @Test
    void chainsAndSinksAreNotCycles() {
        // A waits on B, B waits on C (C not waiting — a sink), D waits on a non-node
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "D", List.of("X")));
        assertTrue(cycles.isEmpty());
    }

    @Test
    void queueBehindACycleJoinsOnlyIfItFeedsBack() {
        // 3-cycle A→B→C→A plus Q queued behind A (Q→A but nobody waits on Q)
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.of("A"),
                "Q", List.of("A")));
        assertEquals(1, cycles.size());
        assertEquals(Set.of("A", "B", "C"), new HashSet<>(cycles.get(0)));
    }

    @Test
    void twoIndependentCyclesStaySeparate() {
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "A", List.of("B"), "B", List.of("A"),
                "C", List.of("D"), "D", List.of("C", "X")));
        assertEquals(2, cycles.size());
        Set<Set<String>> sets = new HashSet<>();
        for (List<String> cycle : cycles) sets.add(new HashSet<>(cycle));
        assertTrue(sets.contains(Set.of("A", "B")));
        assertTrue(sets.contains(Set.of("C", "D")));
    }

    @Test
    void crossLinkedCyclesMergeIntoOneComponent() {
        // A→B→A and C→D→C with B→C and D→A: everything reaches everything
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "A", List.of("B"),
                "B", List.of("A", "C"),
                "C", List.of("D"),
                "D", List.of("C", "A")));
        assertEquals(1, cycles.size());
        assertEquals(Set.of("A", "B", "C", "D"), new HashSet<>(cycles.get(0)));
    }

    @Test
    void deepChainIntoACycleRootsAtTheCycle() {
        // Q3→Q2→Q1→A, A→B→A — mirrors a queue stacked behind a deadlocked pair
        List<List<String>> cycles = TarjanScc.cyclesOf(Map.of(
                "Q3", List.of("Q2"),
                "Q2", List.of("Q1"),
                "Q1", List.of("A"),
                "A", List.of("B"),
                "B", List.of("A")));
        assertEquals(1, cycles.size());
        assertEquals(Set.of("A", "B"), new HashSet<>(cycles.get(0)));
    }
}
