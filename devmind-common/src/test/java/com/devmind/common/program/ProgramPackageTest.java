package com.devmind.common.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-104 程序包树形解包：保留目录、拒绝逃逸路径。 */
class ProgramPackageTest {

    @TempDir
    Path temp;

    private static byte[] zip(Map<String, String> entries, String... rawEntries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            for (String raw : rawEntries) {
                z.putNextEntry(new ZipEntry(raw));
                z.write(new byte[]{1});
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void extractsNestedDirectoriesAndReadsManifest() throws IOException {
        byte[] bytes = zip(Map.of(
                ProgramContract.MANIFEST_NAME, "{\"contractVersion\":\"1\"}",
                "lib/deps/app.jar", "jar",
                "run.sh", "#!/bin/sh\n"));
        Map<String, Path> written = ProgramPackage.extractTree(bytes, temp.resolve("pkg"));
        assertTrue(written.containsKey("lib/deps/app.jar"));
        assertTrue(Files.isRegularFile(temp.resolve("pkg/lib/deps/app.jar")));
        assertTrue(Files.isRegularFile(temp.resolve("pkg/run.sh")));
        assertEquals("{\"contractVersion\":\"1\"}",
                new String(ProgramPackage.readEntry(bytes, ProgramContract.MANIFEST_NAME), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsParentTraversal() throws IOException {
        byte[] bytes = zip(Map.of(), "../evil.sh");
        assertThrows(IOException.class, () -> ProgramPackage.extractTree(bytes, temp.resolve("pkg")));
    }

    @Test
    void rejectsAbsoluteAndDriveLetterPaths() throws IOException {
        assertThrows(IOException.class,
                () -> ProgramPackage.extractTree(zip(Map.of(), "/tmp/evil.sh"), temp.resolve("a")));
        assertThrows(IOException.class,
                () -> ProgramPackage.extractTree(zip(Map.of(), "C:/evil.sh"), temp.resolve("b")));
    }
}
