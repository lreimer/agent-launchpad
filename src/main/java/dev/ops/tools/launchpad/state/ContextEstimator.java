package dev.ops.tools.launchpad.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Estimates the context window usage of a Claude Code session from the tail of its JSONL transcript:
 * the latest main-chain assistant message reports the prompt size in its {@code usage} block.
 */
@ApplicationScoped
public class ContextEstimator {

    private static final Logger LOGGER = Logger.getLogger(ContextEstimator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TAIL_BYTES = 512 * 1024;

    public OptionalLong estimate(String transcriptPath) {
        if (transcriptPath == null || transcriptPath.isBlank()) {
            return OptionalLong.empty();
        }
        Path path;
        try {
            path = Path.of(transcriptPath);
        } catch (InvalidPathException e) {
            return OptionalLong.empty();
        }
        Path fileName = path.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".jsonl") || !Files.isRegularFile(path)) {
            return OptionalLong.empty();
        }

        String tail;
        long start;
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            long size = channel.size();
            start = Math.max(0, size - TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read the tail of the transcript
            }
            tail = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.debugf(e, "Unable to read transcript %s", transcriptPath);
            return OptionalLong.empty();
        }

        String[] lines = tail.split("\n");
        int first = start > 0 ? 1 : 0;
        for (int i = lines.length - 1; i >= first; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode entry;
            try {
                entry = MAPPER.readTree(line);
            } catch (IOException e) {
                continue;
            }
            if (entry.path("isSidechain").asBoolean(false)) {
                continue;
            }
            if ("compact_boundary".equals(entry.path("subtype").asText()) || entry.path("isCompactSummary").asBoolean(false)) {
                // the context was compacted after the latest usage report
                return OptionalLong.empty();
            }
            JsonNode usage = entry.path("message").path("usage");
            if ("assistant".equals(entry.path("type").asText()) && usage.isObject()) {
                return OptionalLong.of(usage.path("input_tokens").asLong(0)
                        + usage.path("cache_creation_input_tokens").asLong(0)
                        + usage.path("cache_read_input_tokens").asLong(0));
            }
        }
        return OptionalLong.empty();
    }
}
