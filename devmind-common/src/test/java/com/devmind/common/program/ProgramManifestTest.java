package com.devmind.common.program;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-104 附录 C.1 清单校验：字段必填、入口安全、构建命令拒绝、运行时匹配。 */
class ProgramManifestTest {

    private static final String VALID = """
            {
              "contractVersion": "1",
              "name": "sync-msg-test",
              "version": "1.2.0",
              "runtime": "JAVA",
              "runtimeVersion": ">=21",
              "entrypoint": "run.sh",
              "targetOs": "LINUX",
              "targetArch": "X86_64"
            }
            """;

    @Test
    void parsesValidManifest() {
        ProgramManifest m = ProgramManifest.parse(VALID);
        assertEquals("sync-msg-test", m.name());
        assertEquals("JAVA", m.runtime());
        assertEquals("LINUX", m.targetOs());
        assertEquals("X86_64", m.targetArch());
        assertEquals("java", m.requiredToolchainKey());
    }

    @Test
    void osAndArchDefaultToAny() {
        ProgramManifest m = ProgramManifest.parse("""
                {"contractVersion":"1","name":"n","version":"1","runtime":"SHELL","entrypoint":"run.sh"}
                """);
        assertEquals("ANY", m.targetOs());
        assertEquals("ANY", m.targetArch());
        assertTrue(m.matchesOs("Windows 11 / amd64"));
        assertTrue(m.matchesArch("arm64"));
        assertEquals(null, m.requiredToolchainKey());
    }

    @Test
    void rejectsWrongContractVersion() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProgramManifest.parse("""
                {"contractVersion":"2","name":"n","version":"1","runtime":"JAVA","entrypoint":"run.sh"}
                """));
        assertTrue(e.getMessage().contains("contractVersion"));
    }

    @Test
    void rejectsEntrypointEscapes() {
        assertThrows(IllegalArgumentException.class, () -> ProgramManifest.validateEntrypoint("../run.sh"));
        assertThrows(IllegalArgumentException.class, () -> ProgramManifest.validateEntrypoint("/opt/run.sh"));
        assertThrows(IllegalArgumentException.class, () -> ProgramManifest.validateEntrypoint("C:/run.sh"));
        assertThrows(IllegalArgumentException.class, () -> ProgramManifest.validateEntrypoint("run.jar"));
        ProgramManifest.validateEntrypoint("bin/run.sh");
    }

    @Test
    void rejectsBuildCommandsInsideEntrypoint() {
        assertThrows(IllegalArgumentException.class,
                () -> ProgramManifest.rejectBuildCommands("run.sh", "mvn -q package && java -jar app.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> ProgramManifest.rejectBuildCommands("run.sh", "npm install"));
        ProgramManifest.rejectBuildCommands("run.sh", "java -cp lib/app.jar com.example.Main");
    }

    @Test
    void runtimeVersionConstraint() {
        ProgramManifest m = ProgramManifest.parse(VALID);
        assertTrue(m.satisfiesRuntimeVersion("21.0.3"));
        assertTrue(m.satisfiesRuntimeVersion("25"));
        assertFalse(m.satisfiesRuntimeVersion("17.0.9"));
        assertFalse(m.satisfiesRuntimeVersion(null));
    }

    @Test
    void extractsArchitectureFromNodeOs() {
        assertEquals("amd64", ProgramManifest.archOfNodeOs("Linux / amd64"));
        assertEquals("arm64", ProgramManifest.archOfNodeOs("macOS / arm64"));
        assertEquals(null, ProgramManifest.archOfNodeOs("Windows"));
    }

    @Test
    void matchesOsAndArch() {
        ProgramManifest m = ProgramManifest.parse(VALID);
        assertTrue(m.matchesOs("Linux 5.15 / amd64"));
        assertFalse(m.matchesOs("Windows 11 / amd64"));
        assertTrue(m.matchesArch("amd64"));
        assertFalse(m.matchesArch("arm64"));
    }
}
