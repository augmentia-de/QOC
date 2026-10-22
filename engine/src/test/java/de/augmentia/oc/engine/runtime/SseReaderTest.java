package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.runtime.provider.client.SseReader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseReaderTest {

    private final SseReader reader = new SseReader();

    @Test
    void readsDataFrames() {
        List<SseReader.SseFrame> frames = read("data: one\n\ndata: two\n\n");
        assertThat(frames).extracting(SseReader.SseFrame::data).containsExactly("one", "two");
    }

    @Test
    void multiLineDataIsJoinedWithNewline() {
        List<SseReader.SseFrame> frames = read("data: line1\ndata: line2\n\n");
        assertThat(frames.getFirst().data()).isEqualTo("line1\nline2");
    }

    @Test
    void eventNameIsCarried() {
        List<SseReader.SseFrame> frames = read("event: delta\ndata: x\n\n");
        assertThat(frames.getFirst().event()).isEqualTo("delta");
    }

    @Test
    void commentsAndIdAndRetryAreIgnored() {
        List<SseReader.SseFrame> frames = read(": comment\nid: 7\nretry: 100\ndata: x\n\n");
        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().data()).isEqualTo("x");
    }

    @Test
    void lastFrameIsFlushedAtEof() {
        List<SseReader.SseFrame> frames = read("data: last");
        assertThat(frames).extracting(SseReader.SseFrame::data).containsExactly("last");
    }

    @Test
    void crlfIsHandled() {
        List<SseReader.SseFrame> frames = read("data: one\r\n\r\ndata: two\r\n\r\n");
        assertThat(frames).extracting(SseReader.SseFrame::data).containsExactly("one", "two");
    }

    @Test
    void emptyBodyHasNoFrames() {
        assertThat(read("")).isEmpty();
    }

    @Test
    void dataWithoutSpaceIsAccepted() {
        assertThat(read("data:x\n\n").getFirst().data()).isEqualTo("x");
    }

    private List<SseReader.SseFrame> read(String text) {
        return reader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
