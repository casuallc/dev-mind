package com.devmind.decisionlab.lab;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

/**
 * CAP-56 渲染节点命令行的共用夹具：把"人在页面上填的字符串"塞进一条 shell 命令的所有规矩集中在这里。
 *
 * <p><b>为什么不各写一份</b>（评测与微调各一套引号规则）：这是一段<b>安全边界</b>——
 * 单引号在 bash 里抑制一切展开（{@code $}、反引号、换行都失效），所以路径参数一律单引号包；
 * 一旦哪天评测那边把它改成双引号、微调这边没改，注入面就出现在"看起来更小心"的那一处。
 * 规矩只有一份，才谈得上"每一处都守住了"。</p>
 *
 * <p>三条规矩，各自对应 runner 侧的一个机制：
 * <ul>
 *   <li>{@link #token}：命令行首 token 必须能原样匹配 execAllowlist（不加引号、不含空白）；</li>
 *   <li>{@link #quoted}：路径/参数单引号包，含单引号/换行/空字节直接拒绝（那种路径本身也不该有）；</li>
 *   <li>{@link #slot}：槽位名的字符集收紧到命令行与路径都安全的一小撮。</li>
 * </ul>
 */
public final class LabCommands {

    private LabCommands() {
    }

    /** 解释器/可执行文件名（首 token）：不加引号、无空白、无引号，否则白名单永远匹配不上 */
    public static String token(String value, String what) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + "为空");
        }
        if (v.chars().anyMatch(Character::isWhitespace)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + " 不能含空白: " + v + "（runner 按首个 token 校验 execAllowlist，"
                            + "含空格的路径会被节点白名单打回；请用不含空格的绝对路径）");
        }
        if (v.contains("\"") || v.contains("'")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + " 不能含引号: " + v);
        }
        return v;
    }

    /** 单引号包裹（bash 里不展开任何东西）；含单引号/换行/空字节的值直接拒绝 */
    public static String quoted(String value, String what) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + "为空");
        }
        if (v.contains("'") || v.contains("\n") || v.contains("\r") || v.contains("\0")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + "含非法字符（单引号/换行）: " + v);
        }
        return "'" + v + "'";
    }

    /** 槽位名（{@code typed-decisions} 这类）：字符集收紧到路径/命令行都安全的一小撮 */
    public static String slot(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "checkpoint 槽位为空");
        }
        if (!v.matches("[A-Za-z0-9._-]+")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "槽位名含非法字符: " + v + "（只允许字母数字与 . _ -）");
        }
        return v;
    }

    /** 计数类参数（epochs/batch/seed）：正整数字面量，不引号（它们本来就是数字） */
    public static String count(long value, long min, long max, String what) {
        if (value < min || value > max) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + " 取值范围 " + min + "-" + max + "（当前 " + value + "）");
        }
        return Long.toString(value);
    }

    /**
     * 浮点参数（学习率）：必须是有限的正常数。
     *
     * <p>{@code Double.toString} 始终用 {@code .} 作小数点（不受 locale 影响），
     * 指数形式（{@code 5.0E-5}）不含任何 shell 元字符，直接当参数传是安全的。</p>
     */
    public static String decimal(double value, double min, double max, String what) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + " 不是有效数字: " + value);
        }
        if (value <= min || value > max) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + " 取值范围 (" + min + ", " + max + "]（当前 " + value + "）");
        }
        return Double.toString(value);
    }

    /** 渲染结果自检：命令必须是单行（runner 逐行取首 token 校验，多一行就多一次打回的机会） */
    public static String singleLine(String command) {
        if (command.contains("\n") || command.contains("\r")) {
            throw new DevMindException(ErrorCode.INTERNAL, "命令渲染出了换行（内部错误）");
        }
        return command;
    }
}
