package com.devmind.flow;

import com.devmind.common.exception.DevMindException;
import com.devmind.flow.dto.SplitDraftItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SplitPlanValidatorTest {

    private SplitDraftItem item(String title, List<Integer> deps) {
        return new SplitDraftItem("DEVELOPMENT", title, "spec", deps);
    }

    @Test
    void 合法清单通过() {
        assertDoesNotThrow(() -> SplitPlanValidator.validate(List.of(
                item("a", List.of()),
                item("b", List.of(0)),
                item("c", List.of(0, 1)))));
    }

    @Test
    void 空清单拒绝() {
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of()));
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(null));
    }

    @Test
    void 下标越界拒绝() {
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                item("a", List.of(1)))));
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                item("a", List.of(-1)))));
    }

    @Test
    void 自依赖拒绝() {
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                item("a", List.of(0)))));
    }

    @Test
    void 环依赖拒绝() {
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                item("a", List.of(1)),
                item("b", List.of(0)))));
        // 间接环 a -> b -> c -> a
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                item("a", List.of(1)),
                item("b", List.of(2)),
                item("c", List.of(0)))));
    }

    @Test
    void 缺标题拒绝() {
        assertThrows(DevMindException.class, () -> SplitPlanValidator.validate(List.of(
                new SplitDraftItem("DEVELOPMENT", " ", "spec", List.of()))));
    }
}
