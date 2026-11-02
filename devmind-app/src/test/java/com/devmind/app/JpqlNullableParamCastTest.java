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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL 可空参数的类型可推断性巡检（PG 回归网）。
 *
 * <p><b>规则</b>：@Query 里 {@code :x is null} 形式的"可空筛选"，必须写成
 * {@code cast(:x as …) is null}；字符串参数出现在 {@code concat('%', :x, '%')} 里时，
 * 也必须写成 {@code concat('%', cast(:x as string), '%')}。</p>
 *
 * <p><b>为什么</b>：PG 的类型推断只认上下文——裸的 {@code ? is null} 是"没有上下文"的参数位，
 * PG 推断不出类型就在 prepare 阶段整条拒绝：{@code could not determine data type of
 * parameter $N}（与参数取值无关，null 和非 null 一样炸）。{@code '%' || ? || '%'} 同样
 * 没有上下文：2026-11-01 用例库在 194(PG) 上把 String 参数推断成 bytea，报
 * {@code function lower(bytea) does not exist}，打开用例库（不传关键字）必现。
 * String/Long 并不因 Java 类型而天然安全。</p>
 *
 * <p><b>为什么必须是静态巡检</b>：H2/MySQL 不做这层校验，本地 dev 库（MySQL）与单测（H2）
 * 全绿，只有 PG 实例会炸；@Query 的 HQL 本身也是合法 HQL，Hibernate 启动期同样不报错。
 * 能提前拦住的只有"看一眼 SQL 长什么样"。</p>
 */
class JpqlNullableParamCastTest {

    /** `:foo is null` 形式的空值判断（必须 CASE_INSENSITIVE——大写 `IS NULL` 曾让
     *  WorklogEntryRepository.findRange 的 LocalDate 裸判断漏网，2026-10-27 在 194(PG) 实炸 42P18） */
    private static final Pattern NULL_CHECK = Pattern.compile(":(\\w+)\\s+is\\s+null", Pattern.CASE_INSENSITIVE);

    /** 空值判断左侧必须是 cast(（允许 `cast (` 这样的空白写法） */
    private static final Pattern CAST_AHEAD = Pattern.compile("cast\\s*\\(\\s*$", Pattern.CASE_INSENSITIVE);

    /** concat 首参拼接里的裸参数：`concat('%', :kw, '%')` 在 PG 上会被推断成 bytea */
    private static final Pattern BARE_CONCAT_PARAM =
            Pattern.compile("concat\\s*\\(\\s*'%'\\s*,\\s*:(\\w+)", Pattern.CASE_INSENSITIVE);

    /** 只看紧邻空值判断的那一小段，避免"同名的另一半条件里有 cast"误判 */
    private static final int LOOKBACK = 24;

    @Test
    void 可空参数的空值判断与字符串拼接必须带_cast() throws Exception {
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
                "PG 无法推断裸参数的类型（could not determine data type / lower(bytea) does not exist），"
                        + "可空筛选必须写成 cast(:x as …) is null，concat 内的字符串参数必须写成 cast(:x as string)：\n  - "
                        + String.join("\n  - ", violations));
    }

    private static void inspect(Class<?> repo, Method method, String jpql, List<String> violations) {
        Map<String, Class<?>> paramTypes = paramTypes(method);
        Matcher nullCheck = NULL_CHECK.matcher(jpql);
        while (nullCheck.find()) {
            String name = nullCheck.group(1);
            Class<?> type = paramTypes.get(name);
            if (type == null) {
                continue;   // 不是本 repository 声明的参数（如 SpEL），无从判断
            }
            String prefix = jpql.substring(Math.max(0, nullCheck.start() - LOOKBACK), nullCheck.start());
            if (!CAST_AHEAD.matcher(prefix).find()) {
                violations.add(repo.getSimpleName() + "." + method.getName()
                        + "() 的 :" + name + "（" + type.getSimpleName() + "）");
            }
        }
        Matcher concatParam = BARE_CONCAT_PARAM.matcher(jpql);
        while (concatParam.find()) {
            String name = concatParam.group(1);
            if (paramTypes.get(name) == String.class) {
                violations.add(repo.getSimpleName() + "." + method.getName()
                        + "() 的 concat('%', :" + name + ", '%')");
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
