package com.devmind.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-84 FR-10 结构感知分块：Markdown 标题切段、代码块整体保留、表格行组、段落优先；
 * 输出带标题路径与原文字符偏移，供 Lucene 索引、证据高亮与原文定位。
 *
 * <p>仍为字符口径（中文按字），不做 tokenizer；超长块才按分隔符/hard-cut 二次切分。</p>
 */
public final class TextChunker {

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "！", "？", "；", "，", "、", " "};
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*$");
    /** FAQ 问题行：Q:/问:/问题:/## Q 等 */
    private static final Pattern FAQ_QUESTION = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:Q|问|问题)\\s*[:：].*$", Pattern.CASE_INSENSITIVE);
    /** FAQ 回答行：A:/答:/回答: */
    private static final Pattern FAQ_ANSWER = Pattern.compile(
            "^(?:A|答|回答)\\s*[:：].*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    private TextChunker() {
    }

    /** 兼容旧调用：只要文本块。 */
    public static List<String> chunk(String text, int size, int overlap) {
        return chunkStructured(text, size, overlap).stream().map(Chunk::text).toList();
    }

    /**
     * @param text    原文（null/空白 → 空列表）
     * @param size    目标块大小（字符）
     * @param overlap 相邻块重叠上限（按完整短块回带，避免从词中间切断）
     */
    public static List<Chunk> chunkStructured(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int target = Math.max(120, size);
        int overlapEff = Math.max(0, Math.min(overlap, target / 4));
        List<Block> blocks = new ArrayList<>();
        for (Block block : splitBlocks(text)) {
            blocks.addAll(expandFaq(block));
        }
        List<Chunk> out = new ArrayList<>();
        List<Block> pending = new ArrayList<>();
        int pendingLen = 0;
        String pendingHeading = null;

        for (Block block : blocks) {
            if (pendingHeading == null) {
                pendingHeading = block.headingPath;
            }
            boolean headingChanged = pendingHeading != null && !pendingHeading.equals(block.headingPath);
            if (headingChanged && !pending.isEmpty()) {
                flush(out, pending, target);
                pending.clear();
                pendingLen = 0;
                pendingHeading = block.headingPath;
            }
            // FAQ 问答对独立成块，不与相邻段落/其它问答对合并
            if (block.standalone && !pending.isEmpty()) {
                flush(out, pending, target);
                pending.clear();
                pendingLen = 0;
            }
            if (block.text.length() >= target) {
                if (!pending.isEmpty()) {
                    Block carry = lastSmall(pending, overlapEff);
                    flush(out, pending, target);
                    pending.clear();
                    pendingLen = 0;
                    if (carry != null) {
                        pending.add(carry);
                        pendingLen = carry.text.length();
                    }
                }
                if (isTable(block)) {
                    splitTableBlock(out, block, target);
                } else {
                    splitLongBlock(out, block, target, overlapEff);
                }
                pendingHeading = block.headingPath;
                continue;
            }
            if (pendingLen + block.text.length() + 2 > target && !pending.isEmpty()) {
                Block carry = lastSmall(pending, overlapEff);
                flush(out, pending, target);
                pending.clear();
                pendingLen = 0;
                if (carry != null && carry.headingPath.equals(block.headingPath)) {
                    pending.add(carry);
                    pendingLen = carry.text.length();
                }
            }
            pending.add(block);
            pendingLen += block.text.length() + 2;
            if (block.standalone) {
                flush(out, pending, target);
                pending.clear();
                pendingLen = 0;
            }
        }
        flush(out, pending, target);
        return out;
    }

    private static Block lastSmall(List<Block> blocks, int overlap) {
        if (overlap <= 0 || blocks.size() < 2) {
            return null;
        }
        Block last = blocks.get(blocks.size() - 1);
        return last.text.length() <= overlap ? last : null;
    }

    private static void flush(List<Chunk> out, List<Block> pending, int target) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : pending) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(b.text);
        }
        String content = sb.toString().strip();
        if (content.isEmpty()) {
            return;
        }
        int start = pending.get(0).start;
        int end = pending.get(pending.size() - 1).end;
        out.add(new Chunk(content, pending.get(0).headingPath, start, end));
    }

    private static void splitLongBlock(List<Chunk> out, Block block, int size, int overlap) {
        String t = block.text;
        if (t.length() <= size) {
            out.add(new Chunk(t, block.headingPath, block.start, block.end));
            return;
        }
        int overlapEff = Math.max(0, Math.min(overlap, size / 2));
        int localStart = 0;
        while (localStart < t.length()) {
            int localEnd = Math.min(localStart + size, t.length());
            if (localEnd < t.length()) {
                localEnd = findBreak(t, localStart, localEnd);
            }
            String piece = t.substring(localStart, localEnd).strip();
            if (!piece.isEmpty()) {
                int pieceStart = t.indexOf(piece, localStart);
                out.add(new Chunk(piece, block.headingPath,
                        block.start + Math.max(0, pieceStart),
                        block.start + Math.max(0, pieceStart) + piece.length()));
            }
            if (localEnd >= t.length()) {
                break;
            }
            int next = localEnd - overlapEff;
            localStart = next > localStart ? next : localEnd;
            while (localStart < localEnd && Character.isWhitespace(t.charAt(localStart))) {
                localStart++;
            }
        }
    }

    private static int findBreak(String t, int start, int end) {
        int min = start + (end - start) / 2;
        for (String sep : SEPARATORS) {
            int idx = t.lastIndexOf(sep, end - 1);
            if (idx >= min) {
                return idx + sep.length();
            }
        }
        return end;
    }

    /** 按空行/标题/代码围栏切块，保留原文字符偏移。 */
    private static List<Block> splitBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = -1;
        int currentEnd = -1;
        boolean inCode = false;
        int pos = 0;
        String currentHeading = "";

        String[] lines = text.split("\\n", -1);
        for (String rawLine : lines) {
            int lineStart = pos;
            int lineEnd = pos + rawLine.length();
            pos = lineEnd + 1;
            String line = rawLine;

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (!inCode && current.length() > 0) {
                    blocks.add(new Block(current.toString(), currentHeading, currentStart, currentEnd));
                    current.setLength(0);
                    currentStart = -1;
                }
                inCode = !inCode;
                appendLine(current, line, lineStart, lineEnd);
                if (currentStart < 0) {
                    currentStart = lineStart;
                }
                currentEnd = lineEnd;
                continue;
            }
            if (inCode) {
                appendLine(current, line, lineStart, lineEnd);
                currentEnd = lineEnd;
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                if (current.length() > 0) {
                    blocks.add(new Block(current.toString(), currentHeading, currentStart, currentEnd));
                    current.setLength(0);
                }
                int level = h.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(h.group(2).strip());
                currentHeading = String.join(" > ", headingStack);
                currentStart = lineStart;
                currentEnd = lineEnd;
                current.append(line);
                continue;
            }
            if (line.isBlank()) {
                if (current.length() > 0) {
                    String blockText = current.toString().strip();
                    if (!blockText.isEmpty()) {
                        blocks.add(new Block(blockText, currentHeading, currentStart, currentEnd));
                    }
                    current.setLength(0);
                    currentStart = -1;
                }
                continue;
            }
            appendLine(current, line, lineStart, lineEnd);
            if (currentStart < 0) {
                currentStart = lineStart;
            }
            currentEnd = lineEnd;
        }
        if (current.length() > 0) {
            String blockText = current.toString().strip();
            if (!blockText.isEmpty()) {
                blocks.add(new Block(blockText, currentHeading, currentStart, currentEnd));
            }
        }
        return blocks;
    }

    private static void appendLine(StringBuilder current, String line, int start, int end) {
        if (current.length() > 0) {
            current.append('\n');
        }
        current.append(line);
    }

    /** FAQ 问答对拆成独立块：避免「问」在上块末尾、「答」在下块开头。 */
    private static List<Block> expandFaq(Block block) {
        String[] lines = block.text.split("\n", -1);
        List<Integer> questions = new ArrayList<>();
        boolean hasAnswer = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (FAQ_QUESTION.matcher(line).matches()) {
                questions.add(i);
            }
            if (FAQ_ANSWER.matcher(line).matches()) {
                hasAnswer = true;
            }
        }
        if (questions.isEmpty() || !hasAnswer) {
            return List.of(block);
        }
        int[] starts = lineStarts(lines);
        List<Block> out = new ArrayList<>();
        for (int q = 0; q < questions.size(); q++) {
            int from = questions.get(q);
            int to = q + 1 < questions.size() ? questions.get(q + 1) : lines.length;
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i].stripTrailing());
            }
            String text = sb.toString().strip();
            if (text.isEmpty()) {
                continue;
            }
            int last = to - 1;
            while (last > from && lines[last].isBlank()) {
                last--;
            }
            out.add(new Block(text, block.headingPath, block.start + starts[from],
                    block.start + starts[last] + lines[last].length(), true));
        }
        return out.isEmpty() ? List.of(block) : out;
    }

    private static boolean isTable(Block block) {
        String[] lines = block.text.split("\n", -1);
        int tableLike = 0;
        for (String line : lines) {
            String s = line.strip();
            if (s.startsWith("|") && s.endsWith("|")) {
                tableLike++;
            }
        }
        return tableLike >= 2 && tableLike * 2 >= lines.length;
    }

    /** 大表按行组分块，每组重复表头；不把表头/数据行从中间切断。 */
    private static void splitTableBlock(List<Chunk> out, Block block, int size) {
        String[] lines = block.text.split("\n", -1);
        int headerLines = lines.length > 1 && TABLE_SEPARATOR.matcher(lines[1].strip()).matches() ? 2 : 1;
        StringBuilder headerBuilder = new StringBuilder();
        for (int h = 0; h < headerLines; h++) {
            if (h > 0) {
                headerBuilder.append('\n');
            }
            headerBuilder.append(lines[h]);
        }
        String header = headerBuilder.toString();
        int[] starts = lineStarts(lines);
        int i = headerLines;
        while (i < lines.length) {
            int groupStartRow = i;
            StringBuilder group = new StringBuilder(header);
            int length = header.length();
            int rows = 0;
            while (i < lines.length) {
                int add = lines[i].length() + 1;
                if (rows > 0 && length + add > size) {
                    break;
                }
                group.append('\n').append(lines[i]);
                length += add;
                rows++;
                i++;
            }
            int start = block.start + starts[groupStartRow];
            int end = block.start + starts[i - 1] + lines[i - 1].length();
            out.add(new Chunk(group.toString(), block.headingPath, start, end));
        }
    }

    private static int[] lineStarts(String[] lines) {
        int[] starts = new int[lines.length];
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = pos;
            pos += lines[i].length() + 1;
        }
        return starts;
    }

    public record Chunk(String text, String headingPath, int startOffset, int endOffset) {
    }

    private record Block(String text, String headingPath, int start, int end, boolean standalone) {
        private Block(String text, String headingPath, int start, int end) {
            this(text, headingPath, start, end, false);
        }
    }
}
