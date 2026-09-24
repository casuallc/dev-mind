package com.devmind.bookmark.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CAP-64 URL 口径：只放 http/https；私有地址判定（探测开关 devmind.bookmark.probe.allow-private 用）。 */
class BookmarkUrlsTest {

    @Test
    void onlyHttpAndHttps() {
        assertEquals("https://nexus.example.com/a?b=1", BookmarkUrls.normalize("  https://nexus.example.com/a?b=1  "));
        assertDoesNotThrow(() -> BookmarkUrls.normalize("http://10.0.0.1:8080/x"));
        for (String bad : new String[]{"file:///c:/x", "jar:file:/x.jar!/", "ftp://h/x", "//h/x", "example.com", ""}) {
            DevMindException e = assertThrows(DevMindException.class, () -> BookmarkUrls.normalize(bad), bad);
            assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        }
    }

    @Test
    void hostMustBePresent() {
        assertThrows(DevMindException.class, () -> BookmarkUrls.normalize("https:///nohost"));
    }

    @Test
    void privateHosts() {
        assertTrue(BookmarkUrls.isPrivateHost("localhost"));
        assertTrue(BookmarkUrls.isPrivateHost("127.0.0.1"));
        assertTrue(BookmarkUrls.isPrivateHost("10.1.2.3"));
        assertTrue(BookmarkUrls.isPrivateHost("172.16.0.9"));
        assertTrue(BookmarkUrls.isPrivateHost("172.31.255.254"));
        assertTrue(BookmarkUrls.isPrivateHost("192.168.1.1"));
        assertTrue(BookmarkUrls.isPrivateHost("169.254.1.1"));
        assertTrue(BookmarkUrls.isPrivateHost("jira"), "无点主机名只可能是内网短名");
        assertTrue(BookmarkUrls.isPrivateHost("[::1]"));
    }

    @Test
    void publicHosts() {
        assertFalse(BookmarkUrls.isPrivateHost("nexus.example.com"));
        assertFalse(BookmarkUrls.isPrivateHost("172.32.0.1"), "172.32 不在 172.16/12 内");
        assertFalse(BookmarkUrls.isPrivateHost("11.0.0.1"));
        assertFalse(BookmarkUrls.isPrivateHost("8.8.8.8"));
        assertFalse(BookmarkUrls.isPrivateHost(null));
    }
}
