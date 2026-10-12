package dev.ops.tools.launchpad.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextEstimatorTest {

    private final ContextEstimator estimator = new ContextEstimator();

    @TempDir
    Path dir;

    @Test
    void latestMainChainAssistantUsage() throws Exception {
        Path transcript = dir.resolve("session.jsonl");
        Files.writeString(transcript, """
                {"type":"user","message":{"role":"user","content":"hi"}}
                {"type":"assistant","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000,"output_tokens":5}}}
                {"type":"assistant","message":{"usage":{"input_tokens":20,"cache_creation_input_tokens":200,"cache_read_input_tokens":2000,"output_tokens":5}}}
                {"type":"assistant","isSidechain":true,"message":{"usage":{"input_tokens":99999}}}
                not json
                """);
        assertEquals(OptionalLong.of(2220), estimator.estimate(transcript.toString()));
    }

    @Test
    void compactBoundaryResetsEstimate() throws Exception {
        Path transcript = dir.resolve("session.jsonl");
        Files.writeString(transcript, """
                {"type":"assistant","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":2000}}}
                {"type":"system","subtype":"compact_boundary"}
                """);
        assertTrue(estimator.estimate(transcript.toString()).isEmpty());
    }

    @Test
    void ignoresMissingAndNonTranscriptFiles() throws Exception {
        Path other = dir.resolve("secret.txt");
        Files.writeString(other, "{\"type\":\"assistant\",\"message\":{\"usage\":{\"input_tokens\":1}}}");
        assertTrue(estimator.estimate(other.toString()).isEmpty());
        assertTrue(estimator.estimate(dir.resolve("missing.jsonl").toString()).isEmpty());
        assertTrue(estimator.estimate(null).isEmpty());
        assertTrue(estimator.estimate(dir.toString()).isEmpty());
    }
}
