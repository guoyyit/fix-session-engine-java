package com.github.guoyyit.fixsessionenginejava.transport;

import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic fault injection plan used by {@link LoopbackTransport}
 * during acceptance testing.
 * <p>
 * Frame indices are counted per direction from 1 for the first frame sent
 * over that direction, so a plan gives reproducible behavior for identical
 * inputs. A frame may be dropped, duplicated, or delayed by one position
 * (swapped with the next frame to arrive) to model simple reordering.
 */
public final class FaultPlan {

    private final Set<Integer> dropped = new HashSet<>();
    private final Set<Integer> duplicated = new HashSet<>();
    private final Set<Integer> delayed = new HashSet<>();

    public FaultPlan dropAt(int frameIndex) {
        dropped.add(frameIndex);
        return this;
    }

    public FaultPlan duplicateAt(int frameIndex) {
        duplicated.add(frameIndex);
        return this;
    }

    public FaultPlan reorderAt(int frameIndex) {
        delayed.add(frameIndex);
        return this;
    }

    public boolean shouldDrop(int frameIndex) {
        return dropped.contains(frameIndex);
    }

    public boolean shouldDuplicate(int frameIndex) {
        return duplicated.contains(frameIndex);
    }

    public boolean shouldDelay(int frameIndex) {
        return delayed.contains(frameIndex);
    }
}
