package com.devmind.common.authz;

import java.util.List;

/**
 * CAP-108 操作目录贡献 SPI：各业务模块声明自己的 OperationSpec，auth 的 OperationRegistry 汇总校验。
 */
public interface OperationSpecProvider {

    List<OperationSpec> operations();
}
