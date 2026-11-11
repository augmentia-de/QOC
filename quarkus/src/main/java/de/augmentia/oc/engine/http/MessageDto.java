package de.augmentia.oc.engine.http;

import java.util.List;

/** Message entry exposed by the REST API. */
public record MessageDto(String id, String type, String text, List<ToolCardDto> tools, String time) {
    public MessageDto {
        if (tools == null) tools = List.of();
        if (time == null) time = "";
    }
}
