package com.devmind.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL 空值判断参数的类型可推断性巡检（PG 回归网）。
 *
 * <p><b>规则</b>：@Query 里 {@code :x is null} 形式的"可空筛选"，若 x 是 {@code java.time.*}
 * 类型，必须写成 {@code cast(:x as …) is null}。</p>
 *
 * <p><b>为什么</b>：PG 的类型推断只认上下文——裸的 {@code ? is null} 是"没有上下文"的参数位，
 * PG 推断不出类型就在 prepare 阶段整条拒绝：{@code ERROR: could not determine data type of
 * parameter $N}（与参数取值无关，null 和非 null 一样炸）。String/Integer 参数侥幸没事，只是
 * 因为 JDBC 驱动恰好知道这些 Java 类型的 OID 会随 Parse 一起送；{@code Instant} 没有，
 * 于是"按时间筛选"一个条件就能把整页打成 500（2026-09-22 172.20.140.224 实录，
 * CAP-55 决策记录页 —— 见 docs/core/开发注意事项.md「PostgreSQL」）。</p>
 *
 * <p><b>为什么必须是静态巡检</b>：H2/MySQL 不做这层校验，本地 dev 库（MySQL）与单测（H2）
 * 全绿，只有 PG 实例会炸；@Query 的 HQL 本身也是合法 HQL，Hibernate 启动期同样不报错。
 * 能提前拦住的只有"看一眼 SQL 长什么样"。</p>
 */
class JpqlNullableParamCastTest {

    /** 驱动送不出类型 OID、只能靠 SQL 上下文自证的类型（实测 Instant；java.time 一律按这条走） */
    private static final Set<String> CONTEXT_ONLY_TYPES = Set.of(
            "java.time.Instant",
            "java.time.LocalDateTime",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime");

    /** `:foo is null` 形式的空值判断 */
    private static final Pattern NULL_CHECK = Pattern.compile(":(\\w+)\\s+is\\s+null");

    /** 空值判断左侧必须是 cast(（允许 `cast (` 这样的空白写法） */
    private static final Pattern CAST_AHEAD = Pattern.compile("cast\\s*\\(\\s*$", Pattern.CASE_INSENSITIVE);

    /** 只看紧邻空值判断的那一小段，避免"同名的另一半条件里有 cast"误判 */
    private static final int LOOKBACK = 24;

    @Test
    void 可空时间参数的空值判断必须带_cast() throws Exception {
        List<Class<?>> repositories = findRepositories();
        assertFalse(repositories.isEmpty(),
                "一个 repository 都没扫到 = 巡检空跑（类路径/过滤条件失效），先修巡检本身");

        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories) {
            for (Method method : repo.getMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                inspect(repo, method, query.value(), violations);
            }
        }

        assertTrue(violations.isEmpty(),
                "PG 无法推断 '? is null' 裸参数的类型（could not determine data type of parameter $N），"
                        + "时间类型的可空筛选必须写成 cast(:x as timestamp) is null：\n  - "
                        + String.join("\n  - ", violations));
    }

    private static void inspect(Class<?> repo, Method method, String jpql, List<String> violations) {
        Map<String, Class<?>> paramTypes = paramTypes(method);
        Matcher m = NULL_CHECK.matcher(jpql);
        while (m.find()) {
            String name = m.group(1);
            Class<?> type = paramTypes.get(name);
            if (type == null || !CONTEXT_ONLY_TYPES.contains(type.getName())) {
                continue;   // 驱动送得出类型，或无从判断类型（不是本规则的对象）
            }
            String prefix = jpql.substring(Math.max(0, m.start() - LOOKBACK), m.start());
            if (!CAST_AHEAD.matcher(prefix).find()) {
                violations.add(repo.getSimpleName() + "." + method.getName()
                        + "() 的 :" + name + "（" + type.getSimpleName() + "）");
            }
        }
    }

    /** @Param 名 → Java 类型 */
    private static Map<String, Class<?>> paramTypes(Method method) {
        Map<String, Class<?>> types = new HashMap<>();
        for (Parameter p : method.getParameters()) {
            Param param = p.getAnnotation(Param.class);
            if (param != null) {
                types.put(param.value(), p.getType());
            }
        }
        return types;
    }

    /**
     * 扫出全仓（所有模块都在 devmind-app 的类路径上）的 Spring Data repository 接口。
     * 默认扫描器不把接口当候选组件，得显式放行。
     */
    private static List<Class<?>> findRepositories() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isInterface()
                                || super.isCandidateComponent(beanDefinition);
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));

        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.devmind")) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (Throwable ignored) {
                // 类加载不了（可选依赖缺席）就跳过，不影响巡检结论
            }
        }
        return found;
    }
}
