package com.devmind.test.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.devmind.common.exception.DevMindException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-69 JUnit XML 解析：正常三态 + 耗时换算 + 多 suite，外加不可信输入加固——
 * XML 来自 runner 上执行的脚本产物，DOCTYPE/外部实体必须被拒（XXE），
 * 根元素不对/畸形要抛出可被 runScript 收口的 DevMindException（而不是静默空结果）。
 */
class JUnitXmlParserTest {

    @Test
    void parsesPassFailSkipAcrossSuites() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="a" tests="2">
                    <testcase classname="LoginSuite" name="登录成功" time="1.25"/>
                    <testcase classname="LoginSuite" name="密码错误拒绝" time="0.5">
                      <failure message="expected 401">AssertionError: got 200</failure>
                    </testcase>
                  </testsuite>
                  <testsuite name="b" tests="1">
                    <testcase name="孤立用例" time="0"><skipped/></testcase>
                  </testsuite>
                </testsuites>
                """;
        List<JUnitXmlParser.ParsedCase> cases = JUnitXmlParser.parse(xml);
        assertEquals(3, cases.size());

        assertEquals("LoginSuite#登录成功", cases.get(0).name());
        assertEquals("pass", cases.get(0).status());
        assertEquals(1250, cases.get(0).durationMs());
        assertNull(cases.get(0).error());

        assertEquals("fail", cases.get(1).status());
        assertTrue(cases.get(1).error().contains("expected 401"));
        assertTrue(cases.get(1).error().contains("got 200"));
        assertEquals(500, cases.get(1).durationMs());

        // 无 classname 时 name 原样，不硬拼 "#"
        assertEquals("孤立用例", cases.get(2).name());
        assertEquals("skip", cases.get(2).status());
    }

    @Test
    void errorElementCountsAsFail() {
        String xml = """
                <testsuite name="x">
                  <testcase classname="C" name="boom" time="2"><error message="NPE"/></testcase>
                </testsuite>
                """;
        List<JUnitXmlParser.ParsedCase> cases = JUnitXmlParser.parse(xml);
        assertEquals(1, cases.size());
        assertEquals("fail", cases.get(0).status());
        assertEquals("NPE", cases.get(0).error());
        assertEquals(2000, cases.get(0).durationMs());
    }

    @Test
    void rejectsDoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE testsuite [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <testsuite><testcase name="&xxe;"/></testsuite>
                """;
        assertThrows(DevMindException.class, () -> JUnitXmlParser.parse(xml));
    }

    @Test
    void rejectsNonJunitRoot() {
        DevMindException ex = assertThrows(DevMindException.class,
                () -> JUnitXmlParser.parse("<html><body>not junit</body></html>"));
        assertTrue(ex.getMessage().contains("testsuite"));
    }

    @Test
    void blankAndGarbageHandled() {
        assertEquals(List.of(), JUnitXmlParser.parse(null));
        assertEquals(List.of(), JUnitXmlParser.parse("  "));
        assertThrows(DevMindException.class, () -> JUnitXmlParser.parse("not xml at all"));
    }
}
