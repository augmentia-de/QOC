package de.augmentia.oc.engine.core.json;

public record DecodeError(String path, String message) {
    public DecodeError {
        if (path == null) path = "$";
        if (message == null) message = "decode error";
    }

    public static DecodeError of(String path, String message) {
        return new DecodeError(path, message);
    }

    @Override
    public String toString() {
        return path + ": " + message;
    }
}