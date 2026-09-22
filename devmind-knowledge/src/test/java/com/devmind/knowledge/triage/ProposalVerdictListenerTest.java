package com.devmind.knowledge.triage;

import com.devmind.common.decision.DecisionRecordSink;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-55 FR-05 裁决留痕监听（mock 掉 sink，不拉 Spring）：三条线——装配了就记、
 * 没装配就静默跳过、sink 炸了不许把异常回吐给"采纳提案"这条业务路径。
 */
class ProposalVerdictListenerTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static ProposalVerdictEvent event() {
        return new ProposalVerdictEvent(7L, "adopt:project", Map.of("adopt_layer", "project"), "alice");
    }

    @Test
    void verdictIsRecordedUnderTheTriageCapability() {
        DecisionRecordSink sink = mock(DecisionRecordSink.class);

        new ProposalVerdictListener(providerOf(sink)).onVerdict(event());

        verify(sink).saveVerdict(eq(KnowledgeTriageService.CAPABILITY), eq("7"),
                eq("adopt:project"), eq(Map.of("adopt_layer", "project")), eq("alice"));
    }

    @Test
    void missingSinkIsSilentlySkipped() {
        DecisionRecordSink absent = null;
        ProposalVerdictListener listener = new ProposalVerdictListener(providerOf(absent));

        assertDoesNotThrow(() -> listener.onVerdict(event()));
    }

    @Test
    void sinkFailureNeverReachesTheCaller() {
        DecisionRecordSink sink = mock(DecisionRecordSink.class);
        doThrow(new IllegalStateException("decision_records 表没了"))
                .when(sink).saveVerdict(anyString(), anyString(), anyString(), any(), anyString());

        ProposalVerdictListener listener = new ProposalVerdictListener(providerOf(sink));

        assertDoesNotThrow(() -> listener.onVerdict(event()),
                "裁决已经生效了：记录写不进不能变成用户看到的报错");
    }
}
