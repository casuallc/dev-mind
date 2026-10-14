package com.devmind.integration.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.integration.model.IntegrationEntity;
import org.junit.jupiter.api.Test;

/**
 * FeishuBotGateway.botEnabled 选择口径：多飞书应用并存时机器人只挑 configJson 带 bot 槽的集成，
 * 文档专用集成（无 bot 槽）必须被跳过。
 */
class FeishuBotGatewayTest {

    private IntegrationEntity entity(String configJson) {
        IntegrationEntity e = new IntegrationEntity();
        e.setName("t");
        e.setConfigJson(configJson);
        return e;
    }

    @Test
    void noConfigJsonMeansBotOff() {
        assertFalse(FeishuBotGateway.botEnabled(entity(null)));
        assertFalse(FeishuBotGateway.botEnabled(entity("")));
        assertFalse(FeishuBotGateway.botEnabled(entity("   ")));
    }

    @Test
    void configWithoutBotSlotMeansBotOff() {
        // 文档导入专用集成：可能有别的扩展槽，但没有 bot 槽
        assertFalse(FeishuBotGateway.botEnabled(entity("{}")));
        assertFalse(FeishuBotGateway.botEnabled(entity("{\"docs\":{\"spaceId\":\"sp1\"}}")));
        // bot 槽不是对象（误配成字符串/布尔）也不算启用
        assertFalse(FeishuBotGateway.botEnabled(entity("{\"bot\":true}")));
    }

    @Test
    void botSlotPresenceMeansOn() {
        assertTrue(FeishuBotGateway.botEnabled(entity("{\"bot\":{}}")));
        assertTrue(FeishuBotGateway.botEnabled(entity("{\"bot\":{\"defaultActor\":\"admin\"}}")));
        assertTrue(FeishuBotGateway.botEnabled(entity("{\"bot\":{\"enabled\":true}}")));
    }

    @Test
    void explicitEnabledFalseWins() {
        assertFalse(FeishuBotGateway.botEnabled(entity("{\"bot\":{\"enabled\":false}}")));
        assertFalse(FeishuBotGateway.botEnabled(
                entity("{\"bot\":{\"enabled\":false,\"defaultActor\":\"admin\"}}")));
    }

    @Test
    void malformedJsonMeansOff() {
        assertFalse(FeishuBotGateway.botEnabled(entity("{not json")));
    }
}
