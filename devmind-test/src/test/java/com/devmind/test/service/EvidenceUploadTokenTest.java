package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.test.config.TestEvidenceProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CAP-104 FR-24：证据上传 token 绑定运行且只能使用一次。 */
class EvidenceUploadTokenTest {

    @Test
    void tokenIsBoundToRunAndSingleUse() {
        TestEvidenceProperties props = new TestEvidenceProperties();
        EvidenceUploadTokenService service = new EvidenceUploadTokenService(props);
        EvidenceUploadTokenService.Issued issued = service.issue("p1", 9L, 3L, "node-1", "screenshot");

        assertNotNull(issued.token());
        assertEquals(64, issued.token().length());

        DevMindException wrongRun = assertThrows(DevMindException.class,
                () -> service.consume(issued.token(), 10L));
        assertEquals(ErrorCode.FORBIDDEN, wrongRun.getErrorCode());

        EvidenceUploadTokenService.TokenInfo info = service.consume(issued.token(), 9L);
        assertEquals("p1", info.projectId());
        assertEquals(3L, info.resultId());
        assertEquals("node-1", info.nodeId());
        assertEquals("SCREENSHOT", info.kind());

        DevMindException reused = assertThrows(DevMindException.class,
                () -> service.consume(issued.token(), 9L));
        assertEquals(ErrorCode.UNAUTHORIZED, reused.getErrorCode());
    }

    @Test
    void missingTokenIsRejected() {
        EvidenceUploadTokenService service = new EvidenceUploadTokenService(new TestEvidenceProperties());
        DevMindException ex = assertThrows(DevMindException.class, () -> service.consume("  ", 1L));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }
}
