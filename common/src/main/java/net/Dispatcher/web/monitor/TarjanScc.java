package net.Dispatcher.web.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iterative Tarjan strongly-connected components over an adjacency map, returning only
 * components with ≥ 2 members (the deadlock cycles). Successors absent from the key set are
 * sinks (blockers that are not themselves waiting) and can never be part of a cycle.
 * MC-free — covered by TarjanSccTest.
 */
final class TarjanScc {
    private TarjanScc() {}

    private record Frame<T>(T node, int childIndex) {}

    static <T> List<List<T>> cyclesOf(Map<T, List<T>> graph) {
        Map<T, Integer> index = new HashMap<>();
        Map<T, Integer> low = new HashMap<>();
        Set<T> onStack = new HashSet<>();
        ArrayDeque<T> stack = new ArrayDeque<>();
        List<List<T>> result = new ArrayList<>();
        int counter = 0;

        for (T root : graph.keySet()) {
            if (index.containsKey(root)) continue;
            ArrayDeque<Frame<T>> work = new ArrayDeque<>();
            work.push(new Frame<>(root, 0));
            while (!work.isEmpty()) {
                Frame<T> frame = work.pop();
                T node = frame.node();
                if (frame.childIndex() == 0) {
                    index.put(node, counter);
                    low.put(node, counter);
                    counter++;
                    stack.push(node);
                    onStack.add(node);
                }
                List<T> children = graph.getOrDefault(node, List.of());
                boolean descended = false;
                for (int i = frame.childIndex(); i < children.size(); i++) {
                    T child = children.get(i);
                    if (!graph.containsKey(child)) continue; // sink
                    if (!index.containsKey(child)) {
                        work.push(new Frame<>(node, i + 1));
                        work.push(new Frame<>(child, 0));
                        descended = true;
                        break;
                    }
                    if (onStack.contains(child)) low.merge(node, index.get(child), Math::min);
                }
                if (descended) continue;
                if (low.get(node).equals(index.get(node))) {
                    List<T> component = new ArrayList<>();
                    T member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(node));
                    if (component.size() >= 2) result.add(component);
                }
                Frame<T> parent = work.peek();
                if (parent != null) low.merge(parent.node(), low.get(node), Math::min);
            }
        }
        return result;
    }
}
