package de.augmentia.qoc.workflowrunner.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared JSON/hash helpers for workflow specifications.
 *
 * <p>Map entries are ordered by key so the content hash is stable across JVMs
 * and storage backends.</p>
 */
public final class WorkflowJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final DateTimeFormatter VERSION_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    /** Process-local tag keeps IDs distinct when several writers snapshot at once. */
    private static final String PROCESS_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    /**
     * Monotonic sequence for snapshots in the same millisecond. The old random
     * suffix alone did not preserve creation order for equal timestamps.
     */
    private static final AtomicLong VERSION_SEQUENCE = new AtomicLong();

    private WorkflowJson() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(WorkflowDefinition definition) {
        try {
            return MAPPER.writeValueAsString(definition);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize workflow", e);
        }
    }

    public static WorkflowDefinition read(String json) {
        try {
            return MAPPER.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize workflow", e);
        }
    }

    public static String hash(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String contentHash(WorkflowDefinition definition) {
        return hash(write(definition));
    }

    /**
     * Generates a sortable, collision-resistant version ID. The monotonic
     * sequence keeps snapshots created within the same millisecond in creation
     * order; the process tag prevents collisions between concurrent writers.
     */
    public static String versionId() {
        String ts = VERSION_FORMAT.format(Instant.now());
        long sequence = VERSION_SEQUENCE.getAndIncrement();
        return ts + "-" + String.format("%08x", sequence) + "-" + PROCESS_TAG;
    }
}
