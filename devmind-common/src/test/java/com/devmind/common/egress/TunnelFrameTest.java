package com.devmind.common.egress;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelFrameTest {

    @Test
    void openRoundTrip() {
        TunnelFrame f = TunnelFrame.decode(TunnelFrame.open(7, "gitlab.corp.com", 443).encode());
        assertEquals(TunnelFrame.TYPE_OPEN, f.type());
        assertEquals(7, f.streamId());
        assertEquals("gitlab.corp.com", f.host());
        assertEquals(443, f.port());
    }

    @Test
    void openAckOkAndFail() {
        TunnelFrame ok = TunnelFrame.decode(TunnelFrame.openAck(3, null).encode());
        assertTrue(ok.isOk());
        assertNull(ok.error());

        TunnelFrame bad = TunnelFrame.decode(TunnelFrame.openAck(3, "目标 host 不在节点放行快照内").encode());
        assertFalse(bad.isOk());
        assertEquals("目标 host 不在节点放行快照内", bad.error());
    }

    @Test
    void dataRoundTrip() {
        byte[] payload = new byte[TunnelFrame.MAX_DATA_BYTES];
        Arrays.fill(payload, (byte) 0x5A);
        TunnelFrame f = TunnelFrame.decode(TunnelFrame.data(9, payload).encode());
        assertEquals(TunnelFrame.TYPE_DATA, f.type());
        assertEquals(9, f.streamId());
        assertArrayEquals(payload, f.payload());
    }

    @Test
    void dataOverLimitRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TunnelFrame.data(1, new byte[TunnelFrame.MAX_DATA_BYTES + 1]).encode());
    }

    @Test
    void closeRstWindowRoundTrip() {
        assertEquals(11, TunnelFrame.decode(TunnelFrame.close(11).encode()).streamId());

        TunnelFrame rst = TunnelFrame.decode(TunnelFrame.rst(12, "boom").encode());
        assertEquals(TunnelFrame.TYPE_RST, rst.type());
        assertEquals(12, rst.streamId());
        assertEquals("boom", rst.error());

        TunnelFrame win = TunnelFrame.decode(TunnelFrame.window(13, 65536).encode());
        assertEquals(TunnelFrame.TYPE_WINDOW, win.type());
        assertEquals(13, win.streamId());
        assertEquals(65536, win.windowBytes());
    }

    @Test
    void decodeGarbageRejected() {
        assertThrows(IllegalArgumentException.class, () -> TunnelFrame.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> TunnelFrame.decode(new byte[]{99}));
    }
}
