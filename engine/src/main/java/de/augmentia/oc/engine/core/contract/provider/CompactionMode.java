package de.augmentia.oc.engine.core.contract.provider;

public sealed interface CompactionMode {
    record Native(boolean encrypted) implements CompactionMode {}
}