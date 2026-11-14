package de.augmentia.qoc.workflowrunner.generate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StripFencesTest {
    @Test
    void stripsMarkdownFences() {
        assertEquals("{\"a\":1}", StripFences.strip("```json\n{\"a\":1}\n```"));
    }

    @Test
    void stripsBareJsonToken() {
        assertEquals("{\"a\":1}", StripFences.strip("json {\"a\":1}"));
    }

    @Test
    void cutsProseAroundBraces() {
        assertEquals("{\"a\":1}", StripFences.strip("Here is the result: {\"a\":1} done"));
    }

    @Test
    void nullReturnsEmpty() {
        assertEquals("", StripFences.strip(null));
    }

    @Test
    void plainJsonUnchanged() {
        assertEquals("{\"a\":1}", StripFences.strip("{\"a\":1}"));
    }
}
