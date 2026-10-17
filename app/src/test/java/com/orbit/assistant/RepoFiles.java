package com.orbit.assistant;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Reads a file from the repository root, for source-contract tests. */
final class RepoFiles {
    private RepoFiles() {}

    static String readRepositoryFile(String relativePath) {
        Path start = Paths.get("").toAbsolutePath();
        Path root = null;
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) {
                root = directory;
                break;
            }
        }
        assertNotNull("repository root was not found above " + start, root);
        Path file = root.resolve(relativePath);
        assertTrue("expected file is missing: " + relativePath, Files.isRegularFile(file));
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }
}
