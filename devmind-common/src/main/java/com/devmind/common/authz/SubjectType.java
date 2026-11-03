package com.devmind.common.authz;

/**
 * CAP-108 主体类型。SYSTEM 仅供服务端登记的可信系统任务，不是用户请求的缺省身份。
 */
public enum SubjectType {
    USER,
    DELEGATED_USER,
    SERVICE,
    SYSTEM
}
