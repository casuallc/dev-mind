package com.devmind.config;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 全局时间格式统一：Instant 一律序列化为 "yyyy-MM-dd HH:mm:ss"（服务器本地时区），
 * 前端拿到即展示格式，不再各自 toLocaleString。
 * 反序列化兼容三种入参：统一格式、ISO-8601（前端 DatePicker toISOString / 乐观更新写入）、epoch 毫秒。
 * 该 ObjectMapper 同时被 REST 与各 WebSocket handler 注入使用，一处配置全局生效。
 */
@Configuration
public class JacksonConfig {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    JsonMapperBuilderCustomizer instantFormatCustomizer() {
        SimpleModule module = new SimpleModule("instant-format")
                .addSerializer(Instant.class, new ValueSerializer<>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
                        gen.writeString(TIME_FORMAT.format(value.atZone(ZoneId.systemDefault())));
                    }
                })
                .addDeserializer(Instant.class, new ValueDeserializer<>() {
                    @Override
                    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
                        if (p.isExpectedNumberIntToken()) {
                            return Instant.ofEpochMilli(p.getLongValue());
                        }
                        String raw = p.getString().trim();
                        try {
                            return LocalDateTime.parse(raw, TIME_FORMAT).atZone(ZoneId.systemDefault()).toInstant();
                        } catch (RuntimeException ignored) {
                            // 非统一格式，按 ISO-8601 兜底（toISOString 等）
                        }
                        try {
                            return Instant.parse(raw);
                        } catch (RuntimeException e) {
                            return ctxt.reportInputMismatch(Instant.class,
                                    "无法解析时间 '%s'，期望 'yyyy-MM-dd HH:mm:ss' 或 ISO-8601", raw);
                        }
                    }
                });
        return builder -> builder.addModule(module);
    }
}
