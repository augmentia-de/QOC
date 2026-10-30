package de.augmentia.qoc.infratest;

/** Response body representing one persisted {@link InfratestEntry}. */
public record InfratestResponse(Long id, String name, String content) {

    static InfratestResponse from(InfratestEntry entry) {
        return new InfratestResponse(entry.getId(), entry.getName(), entry.getContent());
    }
}
