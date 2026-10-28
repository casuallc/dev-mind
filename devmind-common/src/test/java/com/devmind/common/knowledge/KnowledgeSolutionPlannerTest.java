package com.devmind.common.knowledge;
import com.devmind.common.knowledge.KnowledgeRetriever.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSolutionPlannerTest {
    RetrievedChunk evidence(int id,String text){return new RetrievedChunk((long)id,"manual",1L,(long)id,null,null,text,text,0.1,(long)id,"v1","hash","OFFICIAL_MANUAL");}
    @Test void candidateQueryAndEvidenceBudgetsAreHardLimits() {
        var calls=new AtomicInteger();var count=new AtomicInteger();
        var plan=KnowledgeSolutionPlanner.retrieve("build architecture", "ARCHITECTURE",(query,limit)->{
            calls.incrementAndGet();var hits=new ArrayList<RetrievedChunk>();for(int i=0;i<limit;i++)hits.add(evidence(count.incrementAndGet(),"a".repeat(1000)));
            return new Detailed(hits,false,DegradedReason.NO_EMBEDDING);
        });
        assertTrue(calls.get()<=12);assertTrue(plan.candidates()<=200);assertTrue(plan.conservativeTokens()<=12000);
        assertTrue(plan.evidence().size()<=30);assertTrue(plan.exhausted());assertEquals("PARTIAL",plan.status());
    }
    @Test void excessExplicitRequirementsRemainVisibleAsUnexecuted() {
        var question=String.join("\n",java.util.stream.IntStream.range(0,18).mapToObj(i->"requirement-"+i).toList());
        var plan=KnowledgeSolutionPlanner.retrieve(question,"BID",(q,k)->new Detailed(List.of(),false,DegradedReason.NONE));
        assertEquals(12,plan.queries());assertFalse(plan.unexecuted().isEmpty());assertEquals("INSUFFICIENT",plan.status());
    }
    @Test void missingEnvironmentNeverBecomesConfidentPlan() {
        var plan=KnowledgeSolutionPlanner.retrieve("upgrade","DEPLOY",(q,k)->new Detailed(List.of(),false,DegradedReason.NEEDS_CONTEXT));
        assertEquals("NEEDS_CONTEXT",plan.status());assertTrue(plan.rounds()<=2);assertTrue(plan.requirements().stream().allMatch(r->r.status().equals("GAP")));
    }
    @Test void repeatedEvidenceDoesNotBecomeIndependentCorroboration() {
        var hit=evidence(1,"shared evidence");var plan=KnowledgeSolutionPlanner.retrieve("question","GENERAL",(q,k)->new Detailed(List.of(hit,hit),false,DegradedReason.NONE));
        assertEquals(1,plan.evidence().size());assertNotEquals("SUFFICIENT",plan.status());
    }
    @Test void unsupportedCommandAndNumbersAreFlaggedAndWithheld() {
        var result=KnowledgeAnswerCheck.check("Capacity is 100GB [S1]\n```sh\nrm -rf /example\n```",List.of(new KnowledgeAnswerCheck.Evidence(1,"Supported capacity is 10GB.","TEST_REPORT")));
        assertFalse(result.answer().contains("rm -rf"));assertEquals("UNVERIFIED",result.status());
        assertTrue(result.issues().stream().anyMatch(i->i.code().equals("UNSUPPORTED_VALUE_OR_UNIT")));
    }
    @Test void validNumberDoesNotCertifyParaphraseOrBidClaim() {
        var result=KnowledgeAnswerCheck.check("Product supports every database [S1]",List.of(new KnowledgeAnswerCheck.Evidence(1,"A project plans a custom adapter.","BID_RESPONSE")));
        assertEquals("UNVERIFIED",result.status());assertTrue(result.issues().stream().anyMatch(i->i.code().equals("NON_AUTHORITATIVE_CAPABILITY_SOURCE")));
        assertEquals("UNVERIFIED",KnowledgeAnswerCheck.check("No references here",List.of()).status());
    }
}
