package com.devmind.common.security;

/**
 * 服务器连接配置（accessConfig JSON）的凭证加密 SPI（CAP-07 FR-07）。
 * CAP-02 保存/读取服务器时经 ObjectProvider 探测本接口的实现；有实现则敏感字段密文落库。
 * 实现方负责 JSON 解析与 AES 对称加解密（CAP-07 的 CredentialCrypto）。
 */
public interface ServerCredentialCipher {

    /**
     * 加密 accessConfig JSON 中的敏感字段值（password/privateKey/token 等），返回新 JSON 字符串。
     * 非敏感字段与结构保持不变；null/空白原样返回。
     */
    String encryptConfigJson(String accessConfigJson);

    /**
     * 解密 accessConfig JSON 中的敏感字段值，返回可用的明文 JSON。null/空白原样返回。
     */
    String decryptConfigJson(String accessConfigJson);
}
