package com.devmind.common.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative deterministic support checks. Valid citation numbers alone never yield VERIFIED. */
public final class KnowledgeAnswerCheck {
    private static final Pattern CITATION=Pattern.compile("\\[S(\\d+)]");
    private static final Pattern NUMBER_UNIT=Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:GB|MB|KB|TB|ms|秒|分钟|毫秒|并发|QPS|TPS|%)",Pattern.CASE_INSENSITIVE);
    public record Evidence(int number,String content,String sourceClass) { }
    public record Issue(String code,int line,List<Integer> citations) { }
    public record Result(String status,String answer,List<Issue> issues,int citedClaims,int exactSupportedClaims) { }
    private KnowledgeAnswerCheck() { }
    public static Result check(String answer,List<Evidence> evidence) {
        var byNo=new HashMap<Integer,Evidence>();evidence.forEach(e->byNo.put(e.number(),e));
        var issues=new ArrayList<Issue>();var output=new ArrayList<String>();int cited=0,supported=0,lineNo=0;boolean code=false;
        for(String line:(answer==null?"":answer).split("\\n",-1)) {
            lineNo++;if(line.stripLeading().startsWith("```")){code=!code;output.add(line);continue;}
            var numbers=new ArrayList<Integer>();var m=CITATION.matcher(line);
            while(m.find()){try{numbers.add(Integer.parseInt(m.group(1)));}catch(NumberFormatException invalid){issues.add(new Issue("INVALID_CITATION",lineNo,List.of()));}}
            boolean invalid=numbers.stream().anyMatch(n->!byNo.containsKey(n));
            String sources=numbers.stream().filter(byNo::containsKey).map(n->String.valueOf(byNo.get(n).content())).reduce("",(a,b)->a+"\n"+b);
            String plain=CITATION.matcher(line).replaceAll("").replaceFirst("^\\s*(?:[-*>]|\\d+[.)])\\s*","").strip();
            if(invalid)issues.add(new Issue("INVALID_CITATION",lineNo,List.copyOf(numbers)));
            if(!numbers.isEmpty()) {
                cited++;
                if(!plain.isEmpty()&&plain.length()>8&&sources.contains(plain)&&!invalid)supported++;
                else issues.add(new Issue("CLAIM_REQUIRES_REVIEW",lineNo,List.copyOf(numbers)));
                if(numbers.stream().filter(byNo::containsKey).anyMatch(n->Set.of("BID_RESPONSE","GENERATED").contains(String.valueOf(byNo.get(n).sourceClass()))))
                    issues.add(new Issue("NON_AUTHORITATIVE_CAPABILITY_SOURCE",lineNo,List.copyOf(numbers)));
            }
            var units=NUMBER_UNIT.matcher(line);while(units.find())if(!sources.contains(units.group()))issues.add(new Issue("UNSUPPORTED_VALUE_OR_UNIT",lineNo,List.copyOf(numbers)));
            // Commands must be literal evidence. A fenced block with no exact support is withheld.
            if(code && !plain.isBlank() && evidence.stream().noneMatch(e->e.content()!=null&&e.content().contains(plain))) {
                issues.add(new Issue("UNSUPPORTED_COMMAND",lineNo,List.copyOf(numbers)));output.add("# 未验证的命令或配置已隐藏，请核对原文后补充");
            } else output.add(line);
        }
        if(cited==0)issues.add(new Issue("NO_CITATIONS",0,List.of()));
        // Exact quotations can be checked, but completeness and paraphrased conclusions remain unverified.
        String status=cited>0&&supported==cited&&issues.isEmpty()?"PARTIAL":"UNVERIFIED";
        String body=String.join("\n",output);
        if(!issues.isEmpty())body+="\n\n> 证据检查：尚未完成结论支持性验证，请核对来源、适用条件和未覆盖事项后采用。";
        return new Result(status,body,List.copyOf(issues),cited,supported);
    }
}
