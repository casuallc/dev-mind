package com.devmind.bookmark.config;

import com.devmind.common.crypto.SecretCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * CAP-64 FR-05 账号密码加密（复用 CAP-48 抽取的 common {@link SecretCipher}）。
 * 域分隔串 bookmark-account 与 integration/model 域互不通用（发布后禁改）。
 */
@Component
public class BookmarkCipher {

    /** 域分隔串（发布后禁改；改则存量密码全部解不开） */
    static final String DOMAIN = "bookmark-account";

    private final BookmarkProperties props;
    private SecretCipher cipher;

    public BookmarkCipher(BookmarkProperties props) {
        this.props = props;
    }

    /** Spring 启动装配；单测也用同一入口（public 以便测试包直接调用，避免另开一套构造路径）。 */
    @PostConstruct
    public void init() {
        this.cipher = SecretCipher.create(props.getCryptoKey(), DOMAIN, "bookmark-crypto.key");
    }

    public String encrypt(String plaintext) { return cipher.encrypt(plaintext); }

    public String decrypt(String value) { return cipher.decrypt(value); }

    public boolean isEncrypted(String value) { return cipher.isEncrypted(value); }
}
