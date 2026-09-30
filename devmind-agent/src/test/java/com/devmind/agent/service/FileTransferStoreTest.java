package com.devmind.agent.service;

import com.devmind.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-65 文件中转登记：注册/回填/用后即删/断连作废/过期 GC 生命周期。 */
class FileTransferStoreTest {

    @TempDir
    Path dir;

    private FileTransferStore store;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.setFileTransferDir(dir.toString());
        props.setFileTransferExpireMs(600_000);
        store = new FileTransferStore(props);
    }

    @Test
    void uploadLifecycleRegisterReceiveComplete() throws Exception {
        FileTransferStore.Transfer t = store.registerUpload("7", "a.bin", 0, null);
        assertTrue(t.upload());
        assertEquals("7", t.nodeId());
        Files.writeString(t.file(), "hello");
        store.markReceived(t.id(), 5, "abc");
        FileTransferStore.Transfer got = store.get(t.id()).orElseThrow();
        assertEquals(5L, got.size());
        assertEquals("abc", got.sha256());
        store.complete(t.id());
        assertTrue(store.get(t.id()).isEmpty());
        assertFalse(Files.exists(t.file()), "用后即删：临时文件随 complete 删除");
    }

    @Test
    void disconnectInvalidatesNodeTransfers() throws Exception {
        FileTransferStore.Transfer a = store.registerUpload("7", "a", 1, null);
        FileTransferStore.Transfer b = store.registerDownload("7", "b");
        FileTransferStore.Transfer other = store.registerDownload("8", "c");
        Files.writeString(a.file(), "x");
        Files.writeString(b.file(), "y");
        store.onNodeDisconnect("7");
        assertTrue(store.get(a.id()).isEmpty());
        assertTrue(store.get(b.id()).isEmpty());
        assertFalse(Files.exists(a.file()));
        assertFalse(Files.exists(b.file()));
        assertTrue(store.get(other.id()).isPresent(), "其他节点的中转不受影响");
    }

    @Test
    void gcRemovesExpiredOnly() throws Exception {
        AgentProperties props = new AgentProperties();
        props.setFileTransferDir(dir.toString());
        props.setFileTransferExpireMs(-1); // 立即过期
        FileTransferStore expired = new FileTransferStore(props);
        FileTransferStore.Transfer t = expired.registerUpload("7", "a", 1, null);
        Files.writeString(t.file(), "x");
        FileTransferStore.Transfer fresh = store.registerUpload("7", "b", 1, null);
        expired.gc();
        assertTrue(expired.get(t.id()).isEmpty());
        assertFalse(Files.exists(t.file()));
        store.gc();
        assertTrue(store.get(fresh.id()).isPresent(), "未过期的不清");
    }
}
