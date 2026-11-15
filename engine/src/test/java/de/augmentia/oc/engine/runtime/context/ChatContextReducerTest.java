package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.model.ToolContent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextReducerTest {

    @Test
    void disabledByDefault_returnsOriginal() {
        // System property not set -> disabled
        String big = "x".repeat(20000);
        assertThat(ChatContextReducer.enabled()).isFalse();
        assertThat(ChatContextReducer.reduce("read", "c1", big)).isEqualTo(big);
    }

    @Test
    void reduce_smallOutput_notReduced() {
        System.setProperty("qoc.context.reduce-tool-results", "true");
        try {
            String small = "hello world";
            assertThat(ChatContextReducer.reduce("read", "c1", small)).isEqualTo(small);
        } finally {
            System.clearProperty("qoc.context.reduce-tool-results");
        }
    }

    @Test
    void reduce_largeOutput_reduced() {
        System.setProperty("qoc.context.reduce-tool-results", "true");
        try {
            String big = "x".repeat(20000);
            String reduced = ChatContextReducer.reduce("read", "c1", big);
            assertThat(reduced).isNotEqualTo(big);
            assertThat(reduced).contains("reduced=\"true\"");
            assertThat(reduced).contains("read");
            assertThat(reduced).contains("c1");
            assertThat(reduced.length()).isLessThan(big.length());
        } finally {
            System.clearProperty("qoc.context.reduce-tool-results");
        }
    }

    @Test
    void reduce_preservesPrefix() {
        System.setProperty("qoc.context.reduce-tool-results", "true");
        try {
            String big = "A".repeat(20000);
            String reduced = ChatContextReducer.reduce("read", "c1", big);
            assertThat(reduced).startsWith("<tool-result");
            assertThat(reduced).contains("AAA");
        } finally {
            System.clearProperty("qoc.context.reduce-tool-results");
        }
    }

    @Test
    void reduce_nullText_returnsEmpty() {
        assertThat(ChatContextReducer.reduce("read", "c1", null)).isEqualTo("");
    }

    @Test
    void reduceContent_extractsTextItems() {
        System.setProperty("qoc.context.reduce-tool-results", "true");
        try {
            String big = "B".repeat(20000);
            List<ToolContent> content = List.of(new ToolContent.TextItem(big));
            String reduced = ChatContextReducer.reduceContent("grep", "c2", content);
            assertThat(reduced).contains("reduced=\"true\"");
        } finally {
            System.clearProperty("qoc.context.reduce-tool-results");
        }
    }

    @Test
    void threshold_configurable() {
        System.setProperty("qoc.context.reduce-tool-results", "true");
        System.setProperty("qoc.context.reduce-threshold-tokens", "10");
        try {
            assertThat(ChatContextReducer.threshold()).isEqualTo(10);
            String medium = "x".repeat(100); // ~25 tokens > 10
            String reduced = ChatContextReducer.reduce("read", "c1", medium);
            assertThat(reduced).contains("reduced=\"true\"");
        } finally {
            System.clearProperty("qoc.context.reduce-tool-results");
            System.clearProperty("qoc.context.reduce-threshold-tokens");
        }
    }
}