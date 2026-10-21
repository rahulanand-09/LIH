package com.rupeek.l1.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Test helper: a writable copy of the checked-in config directory. */
public final class ConfigDirs {

    public static final Path SOURCE = Path.of("src/main/resources/l1-config");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigDirs() {
    }

    public static Path copy(Path target) {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path p : files.toList()) {
                Path to = target.resolve(SOURCE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rewrites one JSON file in place. */
    public static void edit(Path file, Consumer<ObjectNode> change) {
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(file.toFile());
            change.accept(node);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
