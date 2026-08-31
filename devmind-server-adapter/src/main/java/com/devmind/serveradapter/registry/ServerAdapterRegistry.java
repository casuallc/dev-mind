package com.devmind.serveradapter.registry;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.serveradapter.spi.ServerAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表：按 accessType 路由。新增实现（K8s、CI Runner 等）只需注册一个 ServerAdapter bean。
 */
@Component
public class ServerAdapterRegistry {

    private final Map<String, ServerAdapter> byType = new LinkedHashMap<>();

    public ServerAdapterRegistry(List<ServerAdapter> adapters) {
        for (ServerAdapter a : adapters) {
            byType.put(a.supportedType(), a);
        }
    }

    public ServerAdapter require(String accessType) {
        ServerAdapter a = byType.get(accessType);
        if (a == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "不支持的访问类型: " + accessType + "（可用: " + String.join("/", byType.keySet()) + "）");
        }
        return a;
    }
}
