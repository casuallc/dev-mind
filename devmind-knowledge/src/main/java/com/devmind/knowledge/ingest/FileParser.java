package com.devmind.knowledge.ingest;

import com.devmind.knowledge.config.KnowledgeProperties;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/**
 * CAP-84 FR-03 文件解析：Markdown/TXT 原生读取（UTF-8 → GB18030 回退），
 * PDF/Word/PPT/Excel/HTML 先由 Tika 输出结构化 XHTML，再经 {@link HtmlToMarkdown}
 * 转为 Markdown（保留标题/列表/表格/代码块）。解析为空或超限显式失败，不静默产生空文档。
 */
@Component
public class FileParser {

    private static final Logger log = LoggerFactory.getLogger(FileParser.class);
    private static final Set<String> NATIVE_TEXT = Set.of("md", "markdown", "txt");

    private final KnowledgeProperties props;
    private final Tika tika = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();

    public FileParser(KnowledgeProperties props) {
        this.props = props;
    }

    public String parse(Path path, String fileName) {
        String ext = extension(fileName);
        try {
            String text = NATIVE_TEXT.contains(ext)
                    ? decodeText(Files.readAllBytes(path))
                    : parseWithTika(path);
            String normalized = normalize(text);
            if (normalized.isBlank()) {
                throw new FileParseException("PARSE_EMPTY",
                        "解析结果为空（可能是扫描版 PDF 或纯图片文档）");
            }
            int max = props.getUpload().getMaxParsedChars();
            if (normalized.length() > max) {
                normalized = normalized.substring(0, max);
                log.warn("解析文本超限已截断: file={} max={}", fileName, max);
            }
            return normalized;
        } catch (FileParseException e) {
            throw e;
        } catch (Exception e) {
            throw new FileParseException("PARSE_FAILED", "解析失败: " + e.getMessage(), e);
        }
    }

    public String detectMime(Path path, String fileName) {
        try {
            return tika.detect(path);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private String parseWithTika(Path path) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        int limit = props.getUpload().getMaxParsedChars();
        // XHTML 标记比纯文本膨胀数倍，限流按标记体积放大；最终正文仍按 maxParsedChars 截断
        ToXMLContentHandler xhtml = new ToXMLContentHandler();
        WriteOutContentHandler handler = new WriteOutContentHandler(xhtml, Math.max(limit * 8, limit + 1_000_000));
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, handler, metadata, context);
        } catch (SAXException e) {
            if (e.getCause() instanceof WriteLimitReachedException) {
                throw new FileParseException("PARSE_TOO_LARGE", "解析文本超过上限 " + limit + " 字符");
            }
            throw e;
        }
        return HtmlToMarkdown.convert(xhtml.toString());
    }

    /** 优先严格 UTF-8；失败回退 GB18030（大量存量中文 txt 是 GBK 编码）。 */
    private static String decodeText(byte[] bytes) {
        String text = strictDecode(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = strictDecode(bytes, Charset.forName("GB18030"));
        }
        if (text == null) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .replace("\f", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
