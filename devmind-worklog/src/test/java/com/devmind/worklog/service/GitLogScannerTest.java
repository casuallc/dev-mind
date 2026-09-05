package com.devmind.worklog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitLogScannerTest {

    @Test
    void hostOfParsesHttpsAndScpStyle() {
        assertEquals("gitlab.example.com", GitLogScanner.hostOf("https://gitlab.example.com/group/repo.git"));
        assertEquals("gitlab.example.com", GitLogScanner.hostOf("git@gitlab.example.com:group/repo.git"));
        assertEquals("github.com", GitLogScanner.hostOf("https://github.com/a/b"));
        assertNull(GitLogScanner.hostOf(null));
        assertNull(GitLogScanner.hostOf("  "));
        assertNull(GitLogScanner.hostOf("/local/path/only"));
    }
}
