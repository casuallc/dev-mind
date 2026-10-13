package com.devmind.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CommandParserTest {

    @Test
    void nonCommandReturnsNull() {
        assertNull(CommandParser.parse(null));
        assertNull(CommandParser.parse(""));
        assertNull(CommandParser.parse("  你好 "));
        assertNull(CommandParser.parse("/"));
        assertNull(CommandParser.parse("/ "));
    }

    @Test
    void simpleCommand() {
        CommandParser.Command c = CommandParser.parse("/help");
        assertEquals("help", c.name());
        assertEquals("", c.args());
    }

    @Test
    void caseInsensitiveWithArgs() {
        CommandParser.Command c = CommandParser.parse("  /BIND  我的项目 ");
        assertEquals("bind", c.name());
        assertEquals("我的项目", c.args());
    }

    @Test
    void argsKeepInnerSpaces() {
        CommandParser.Command c = CommandParser.parse("/session 做一个 登录 页");
        assertEquals("session", c.name());
        assertEquals("做一个 登录 页", c.args());
    }

    @Test
    void fullWidthSpaceSeparates() {
        CommandParser.Command c = CommandParser.parse("/bind　proj-1");
        assertEquals("bind", c.name());
        assertEquals("proj-1", c.args());
    }
}
