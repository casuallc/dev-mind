package com.devmind.agent.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CAP-106 FR-01 节点级环境变量编解码与校验（agent_nodes.env_json JSON 对象串 ↔ Map）。
 * 明文落库明文展示（定位是机器配置不是凭证，<b>禁存密钥</b>，密钥走测试环境 secrets 链路）。
 * 执行下发时作 env 合并最低优先基底：节点 env &lt; 测试环境 variables &lt; 用例/步骤 env。
 */
public final class AgentNodeEnv {

    /** 条数上限 */
    public static final int MAX_ENTRIES = 64;
    /** 单值长度上限（字符） */
    public static final int MAX_VALUE_LEN = 4096;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 环境变量 key 白名单（防注入：=、换行、空格不进 key） */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private AgentNodeEnv() {
    }

    /**
     * 校验并归一化（空值 = 删除该 key，与 FR-01 语义一致）；非法抛 BAD_REQUEST。
     * null 输入 = 不动（全量语义的分层在调用方），空表合法（= 清空）。
     */
    public static Map<String, String> validate(Map<String, String> env) {
        if (env == null) {
            return null;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().strip();
            if (key.isEmpty()) {
                continue;
            }
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "环境变量名非法（须匹配 [A-Za-z_][A-Za-z0-9_]*）: " + key);
            }
            String value = e.getValue();
            if (value == null || value.isEmpty()) {
                continue; // 空值 = 删除该 key
            }
            if (value.length() > MAX_VALUE_LEN) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "环境变量值超长（" + value.length() + " > " + MAX_VALUE_LEN + " 字符）: " + key);
            }
            cleaned.put(key, value);
        }
        if (cleaned.size() > MAX_ENTRIES) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "环境变量条数超限（" + cleaned.size() + " > " + MAX_ENTRIES + "）");
        }
        return cleaned;
    }

    /** 序列化落库：空表/null → null（清空语义）。 */
    public static String toJson(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(env);
    }

    /** 解析库值：null/空白/坏 JSON → 空表（坏数据按未配置对待）。 */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> env = MAPPER.readValue(json, new TypeReference<>() {
            });
            return env == null ? Map.of() : env;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
