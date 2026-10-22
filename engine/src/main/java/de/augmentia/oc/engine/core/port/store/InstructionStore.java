package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;

/**
 * Instruction state and blob store. The delta carries only hashes; the
 * privileged text is stored once in the blob store.
 */
public interface InstructionStore {

    Option<InstructionState> state(SessionId session);

    void putState(SessionId session, InstructionState state);

    List<InstructionEntry> entries(SessionId session);

    void putEntry(SessionId session, InstructionEntry entry);

    void removeEntry(SessionId session, String key);

    Option<String> blob(String hash);

    void putBlob(String hash, String value);
}
