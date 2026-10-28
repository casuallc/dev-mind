package com.devmind.common.knowledge;

import com.devmind.common.knowledge.KnowledgeRetriever.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/** Bounded, deterministic planning. Retrieval coverage is never presented as fact verification. */
public final class KnowledgeSolutionPlanner {
    public static final int MAX_QUERIES=12,MAX_CANDIDATES=200,MAX_EVIDENCE_TOKENS=12_000;
    public record Requirement(String id,String section,String question,List<Long> evidenceIds,String status) { }
    public record Plan(List<Requirement> requirements,List<RetrievedChunk> evidence,int rounds,int queries,
                       int candidates,int conservativeTokens,boolean exhausted,String status,List<String> unexecuted) { }
    private KnowledgeSolutionPlanner() { }
    public static List<String> sections(String template) {
        return switch(template==null?"GENERAL":template) {
            case "ARCHITECTURE" -> List.of("需求与约束","选型与组件","数据流与集成","部署拓扑","安全与非功能指标","备选与取舍","验收与风险");
            case "TEST" -> List.of("测试范围与需求","测试环境与版本","功能测试用例","性能与安全测试","测试数据与方法","通过标准与验收","风险与待测项");
            case "BID" -> List.of("招标条款与要求","逐项响应与满足程度","产品标准能力证明","定制与规划边界","测试证明材料","偏差与风险","交付与验收");
            case "FAULT" -> List.of("现象与影响","前提与环境","定位证据","隔离止损","恢复步骤","验证与回滚");
            case "DEPLOY" -> List.of("目标与环境","变更前检查","部署与配置","变更后验证","风险与观察期","回滚与验收");
            case "DEV" -> List.of("需求与约束","接口与数据结构","设计取舍","实现步骤","配置与兼容","自测与验收");
            case "FAQ" -> List.of("问题与直接答复","适用环境","操作与例外","验证与来源");
            default -> List.of("需求与约束","推荐方案与依据","设计与实施","配置与前提","验证与验收","风险与待确认项");
        };
    }
    public static Plan retrieve(String question,String template,BiFunction<String,Integer,Detailed> search) {
        var tasks=new ArrayList<String>();
        // Explicit user requirements take precedence over generic sections when budgets are tight.
        for(String line:question.split("[\\r\\n；;]+")) if(!line.isBlank() && !tasks.contains(line.strip()))tasks.add(line.strip());
        for(String section:sections(template))if(!tasks.contains(section))tasks.add(section);
        var unexecuted=new ArrayList<String>();
        if(tasks.size()>MAX_QUERIES){unexecuted.addAll(tasks.subList(MAX_QUERIES,tasks.size()));tasks=new ArrayList<>(tasks.subList(0,MAX_QUERIES));}
        var requirements=new ArrayList<Requirement>();var all=new LinkedHashMap<String,RetrievedChunk>();
        int queries=0,candidates=0,rounds=1; boolean context=false;
        for(String task:tasks) {
            int limit=Math.max(1,(MAX_CANDIDATES-candidates)/Math.max(1,tasks.size()-queries));
            var result=search.apply(question+"\n本次需要的证据："+task,limit);queries++;
            var hits=result.chunks()==null?List.<RetrievedChunk>of():result.chunks();
            if(result.candidateCount()>limit)throw new IllegalStateException("Retriever exceeded the assigned candidate budget");
            candidates+=result.candidateCount();context|=result.degradedReason()==DegradedReason.NEEDS_CONTEXT;
            for(var hit:hits.stream().limit(limit).toList())all.putIfAbsent(key(hit),hit);
            requirements.add(new Requirement("R"+queries,task,task,hits.stream().limit(limit).map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList(),hits.isEmpty()?"GAP":"RETRIEVED_UNVERIFIED"));
        }
        // One targeted retry for gaps. Never recurse and never exceed either budget.
        for(int i=0;i<requirements.size() && queries<MAX_QUERIES && candidates<MAX_CANDIDATES;i++) {
            var r=requirements.get(i);if(!r.evidenceIds().isEmpty())continue;
            rounds=2;int limit=Math.min(20,MAX_CANDIDATES-candidates);
            var result=search.apply(r.question()+"\n"+question,limit);queries++;
            var hits=result.chunks()==null?List.<RetrievedChunk>of():result.chunks();
            if(result.candidateCount()>limit)throw new IllegalStateException("Retriever exceeded the assigned candidate budget");
            candidates+=result.candidateCount();
            context|=result.degradedReason()==DegradedReason.NEEDS_CONTEXT;
            for(var hit:hits.stream().limit(limit).toList())all.putIfAbsent(key(hit),hit);
            requirements.set(i,new Requirement(r.id(),r.section(),r.question(),hits.stream().limit(limit).map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList(),hits.isEmpty()?"GAP":"RETRIEVED_UNVERIFIED"));
        }
        var selected=new ArrayList<RetrievedChunk>();var perRelease=new HashMap<Object,Integer>();int tokens=0;
        for(var hit:all.values()) {
            Object family=hit.releaseId()==null?hit.entryId():hit.releaseId();
            int cost=(hit.content()==null?"":hit.content()).getBytes(StandardCharsets.UTF_8).length; // conservative upper bound, no provider tokenizer assumption
            if(tokens+cost>MAX_EVIDENCE_TOKENS || selected.size()>=30 || perRelease.getOrDefault(family,0)>=3)continue;
            selected.add(hit);tokens+=cost;perRelease.merge(family,1,Integer::sum);
        }
        var included=new HashSet<>(selected.stream().map(RetrievedChunk::chunkId).toList());
        requirements=requirements.stream().map(r->{var ids=r.evidenceIds().stream().filter(included::contains).toList();return new Requirement(r.id(),r.section(),r.question(),ids,ids.isEmpty()?"GAP":"RETRIEVED_UNVERIFIED");}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean exhausted=!unexecuted.isEmpty()||selected.size()<all.size()||requirements.stream().anyMatch(r->r.evidenceIds().isEmpty())&&queries==MAX_QUERIES;
        return new Plan(List.copyOf(requirements),List.copyOf(selected),rounds,queries,candidates,tokens,exhausted,
                context?"NEEDS_CONTEXT":selected.isEmpty()?"INSUFFICIENT":"PARTIAL",List.copyOf(unexecuted));
    }
    private static String key(RetrievedChunk c){return c.releaseId()+":"+c.chunkId()+":"+KnowledgeDigests.sha256(String.valueOf(c.content()));}
}
