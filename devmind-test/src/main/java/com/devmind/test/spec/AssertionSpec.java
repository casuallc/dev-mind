package com.devmind.test.spec;

/**
 * CAP-104 断言输入。source 决定 selector 语义：
 * HTTP_STATUS（ignore）| HEADER（header 名）| JSON_PATH（$.a.b[0]）| BODY（ignore）|
 * STDOUT/STDERR（ignore）| EXIT_CODE（ignore）| FILE_EXISTS/FILE_SHA256（相对工作目录路径）。
 */
public record AssertionSpec(String source, String selector, String op, String expected) {

    public static final String HTTP_STATUS = "HTTP_STATUS";
    public static final String HEADER = "HEADER";
    public static final String JSON_PATH = "JSON_PATH";
    public static final String BODY = "BODY";
    public static final String STDOUT = "STDOUT";
    public static final String STDERR = "STDERR";
    public static final String EXIT_CODE = "EXIT_CODE";
    public static final String FILE_EXISTS = "FILE_EXISTS";
    public static final String FILE_SHA256 = "FILE_SHA256";
    /** 程序型用例 result.json 自带断言（M1.5） */
    public static final String CUSTOM = "CUSTOM";

    public static final String OP_EQUALS = "EQUALS";
    public static final String OP_CONTAINS = "CONTAINS";
    public static final String OP_NOT_CONTAINS = "NOT_CONTAINS";
    public static final String OP_REGEX = "REGEX";
    public static final String OP_EXISTS = "EXISTS";
    public static final String OP_NOT_EXISTS = "NOT_EXISTS";
}
