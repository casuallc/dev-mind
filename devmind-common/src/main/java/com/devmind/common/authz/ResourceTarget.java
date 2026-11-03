package com.devmind.common.authz;

/**
 * CAP-108 资源目标：授权所需的最小事实引用（不携带实体正文）。
 * projectId/ownerId 必须由服务端反查真实归属填充，禁止原样采用客户端传入值。
 */
public record ResourceTarget(String targetType, String id, String projectId, String ownerId) {

    public static final String TYPE_NONE = "NONE";

    public static ResourceTarget none() {
        return new ResourceTarget(TYPE_NONE, null, null, null);
    }

    public static ResourceTarget project(String projectId) {
        return new ResourceTarget("PROJECT", projectId, projectId, null);
    }

    /** 项目内对象：id 为对象主键，projectId 为服务端反查的真实归属。 */
    public static ResourceTarget inProject(String targetType, String id, String projectId) {
        return new ResourceTarget(targetType, id, projectId, null);
    }

    /** 个人域对象：ownerId 由服务端从主体或存储归属推导。 */
    public static ResourceTarget owned(String targetType, String id, String ownerId) {
        return new ResourceTarget(targetType, id, null, ownerId);
    }
}
