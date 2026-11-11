package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;

import java.util.List;

/**
 * Detects endless loops: same tool call repeated N times in a row (FR-3.2, Error matrix).
 */
public interface LoopDetector {

    /** Returns true if the new call would constitute a loop. */
    boolean isLoop(List<ToolCall> recentCalls, ToolCall newCall, int threshold);

    /** Default implementation: compares tool name + input JSON. */
    final class Default implements LoopDetector {
        @Override
        public boolean isLoop(List<ToolCall> recentCalls, ToolCall newCall, int threshold) {
            if (newCall == null || recentCalls == null || recentCalls.isEmpty()) return false;
            int t = Math.max(2, threshold);
            int count = 0;
            String newSig = signature(newCall);
            for (int i = recentCalls.size() - 1; i >= 0; i--) {
                if (signature(recentCalls.get(i)).equals(newSig)) {
                    count++;
                } else {
                    break;
                }
            }
            return count >= t - 1;
        }

        private static String signature(ToolCall call) {
            if (call == null) return "";
            return call.name().value() + "|" + (call.input() == null ? "" : call.input().toString());
        }
    }
}
