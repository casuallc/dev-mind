package com.devmind.knowledge.ingest;

/** 解析失败：message 是给用户看的错误码/原因（PARSE_EMPTY / PARSE_TOO_LARGE / 具体异常摘要）。 */
public class FileParseException extends RuntimeException {

    private final String code;

    public FileParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FileParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
