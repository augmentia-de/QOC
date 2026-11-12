package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;

/** No-op instruction store for minimal (in-memory) mode. */
public final class NoOpInstructionStore implements de.augmentia.oc.engine.core.port.store.InstructionStore {
    @Override public Option<InstructionState> state(SessionId session) { return Option.none(); }
    @Override public void putState(SessionId session, InstructionState state) { /* no-op */ }
    @Override public List<InstructionEntry> entries(SessionId session) { return List.of(); }
    @Override public void putEntry(SessionId session, InstructionEntry entry) { /* no-op */ }
    @Override public void removeEntry(SessionId session, String key) { /* no-op */ }
    @Override public Option<String> blob(String hash) { return Option.none(); }
    @Override public void putBlob(String hash, String value) { /* no-op */ }
}