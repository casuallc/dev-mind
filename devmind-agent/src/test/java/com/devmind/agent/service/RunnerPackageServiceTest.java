package com.devmind.agent.service;

import com.devmind.agent.config.AgentProperties;
import com.devmind.agent.dto.RunnerPackageView;
import com.devmind.agent.model.RunnerPackageEntity;
import com.devmind.agent.repo.RunnerPackageRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** RunnerPackageService：版本提取 / sha256 / 落盘替换 / 非法包拒绝（用内存 stub repo，不拉起 Spring）。 */
class RunnerPackageServiceTest {

    @TempDir
    Path dir;

    private RunnerPackageService service;

    /** 极简内存 repo：只支撑 findById/save。 */
    private static class InMemoryRepo {
        private RunnerPackageEntity row;

        RunnerPackageRepository create() {
            RunnerPackageRepository repo = mock(RunnerPackageRepository.class);
            org.mockito.Mockito.when(repo.findById(RunnerPackageService.PKG_ID))
                    .thenAnswer(inv -> Optional.ofNullable(row));
            org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        row = inv.getArgument(0);
                        return row;
                    });
            return repo;
        }
    }

    private InMemoryRepo repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepo();
        AgentProperties props = new AgentProperties();
        props.setRunnerPackageDir(dir.toString());
        service = new RunnerPackageService(repo.create(), props);
    }

    private static byte[] fakeJar(String version, boolean withSelfUpdater) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("runner-version.txt"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (withSelfUpdater) {
                zip.putNextEntry(new ZipEntry("com/devmind/agent/runner/SelfUpdater.class"));
                zip.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static MockMultipartFile jarFile(byte[] bytes) {
        return new MockMultipartFile("file", "devmind-agent-runner.jar", "application/java-archive", bytes);
    }

    @Test
    void uploadExtractsVersionAndSha256AndWritesFile() throws Exception {
        byte[] jar = fakeJar("0.2.0", true);
        RunnerPackageView view = service.upload(jarFile(jar), "admin");

        assertEquals("0.2.0", view.version());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)), view.sha256());
        assertEquals(jar.length, view.sizeBytes());
        assertEquals("admin", view.uploadedBy());
        assertTrue(Files.exists(dir.resolve("runner.jar")));
        assertEquals(jar.length, Files.size(dir.resolve("runner.jar")));
        assertEquals("0.2.0", service.current().version());
    }

    @Test
    void reuploadReplacesInPlace() throws Exception {
        service.upload(jarFile(fakeJar("0.1.0", true)), "admin");
        byte[] newer = fakeJar("0.2.0", true);
        RunnerPackageView view = service.upload(jarFile(newer), "bob");

        assertEquals(RunnerPackageService.PKG_ID, view.id());
        assertEquals("0.2.0", view.version());
        assertEquals(newer.length, Files.size(dir.resolve("runner.jar")));
    }

    @Test
    void rejectsNonJar() {
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        assertThrows(DevMindException.class, () -> service.upload(txt, "admin"));
    }

    @Test
    void rejectsJarWithoutVersion() {
        assertThrows(DevMindException.class, () -> service.upload(jarFile(new byte[]{1, 2, 3}), "admin"));
    }

    @Test
    void rejectsUnfilteredPlaceholderVersion() throws Exception {
        // 未过滤的 @project.version@ 占位（历史 bug 重现防护）
        assertThrows(DevMindException.class,
                () -> service.upload(jarFile(fakeJar("@project.version@", true)), "admin"));
    }

    @Test
    void rejectsJarWithoutSelfUpdater() throws Exception {
        assertThrows(DevMindException.class, () -> service.upload(jarFile(fakeJar("0.2.0", false)), "admin"));
    }

    @Test
    void currentMissingPackageIs404() {
        DevMindException e = assertThrows(DevMindException.class, service::current);
        assertEquals(404, e.getErrorCode().getStatus());
    }
}
