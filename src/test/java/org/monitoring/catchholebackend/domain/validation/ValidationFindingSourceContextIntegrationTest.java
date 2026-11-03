package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오류 리포트 문맥 블록(2026-11-03): 상세 blocks[]의 처음 앞뒤 2줄과 GET .../source-context 더 보기.
 * 합성 원고: 이번 원고 101화(CRLF·빈 줄 사이 문단 6줄, 질문 위치는 넷째 줄), 이전 회차 100화(LF 7줄, 근거 문장은 넷째 줄),
 * 고정 기준 B2=S2 "합성 종족"(서식지=북부). 블록 병합(2026-11-03)은 같은 출처 인용의 처음 창이 겹치거나 맞닿을 때 한 블록이다.
 */
@SpringBootTest(properties="spring.config.import=") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@DisplayName("오류 리포트 문맥 블록: 근거 원문의 앞뒤 줄과 더 보기")
class ValidationFindingSourceContextIntegrationTest extends ValidationReviewFixture {
    @org.springframework.beans.factory.annotation.Autowired org.monitoring.catchholebackend.domain.validation.mapper.ValidationDataMapper dataMapper;
    static final String TARGET=String.join("\r\n","처음 합성 원고","","둘째 줄 합성","","셋째 줄 합성","","넷째 줄 대상 문장","","다섯째 줄 합성","","여섯째 줄 합성");
    static final String PRIOR=String.join("\n","합성 앞 원고","가 줄","","나 줄","다 줄 근거 문장이 있다","라 줄","","마 줄","바 줄");
    static final String MID_QUOTE="대상 문장",CHUNK_TEXT="다 줄 근거 문장이 있다",BASIS="근거 문장이 있다";
    /** 멀리 떨어진 위치용 12줄 원고(첫 줄은 기존 합성 원고와 같아 오탈자 위치가 그대로다). */
    static final String LONG_TARGET=String.join("\r\n","처음 합성 원고","","둘째 줄","셋째 줄","넷째 줄","다섯째 줄","여섯째 줄","일곱째 줄","여덟째 줄","아홉째 줄","열째 줄 먼 문장","열한째 줄","열두째 줄");
    String targetText=TARGET;

    static int at(String text,String part) {return text.codePointCount(0,text.indexOf(part));}
    static int cp(String text) {return text.codePointCount(0,text.length());}
    static Map<String,Object> span(int start,String quote) {return Map.of("source_ref","T1","start",start,"end",start+cp(quote),"quote",quote);}
    Map<String,Object> prior(int start,String quote) {return Map.of("sourceRef","R1","sourceHash",priorHash,"sourceVersion",prior.getContentS3Version(),"start",start,"end",start+cp(quote),"quote",quote);}

    /** 여러 줄 합성 원고로 실행을 시작한다. 이번 원고 hash는 접수 자격을 바꾸지 않도록 행만 고친다. */
    JsonNode beginMultiLine() throws Exception {
        em.createNativeQuery("update episodes set content_hash=:h where id=:id").setParameter("h",sha(targetText)).setParameter("id",target.getId()).executeUpdate();
        em.flush();em.refresh(target);
        when(storage.getText(anyString())).thenAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?targetText:PRIOR);
        priorHash=sha(PRIOR);prior.updateContentStorage(prior.getContentS3Key(),prior.getContentS3Version(),priorHash);em.flush();
        start();return claim();
    }

    record Published(JsonNode job,UUID report) {}

    /** 넷째 줄 위치를 묻는 질문 하나를 계획하고, 서식지 설정값(B2)과 100화 근거 문장을 옮긴 CONFLICT로 판정한 뒤 오탈자까지 끝낸다. */
    Published decided() throws Exception {
        int chunk=at(PRIOR,CHUNK_TEXT),basis=at(PRIOR,BASIS);
        return decided(List.of(MID_QUOTE),List.of(bQuote("북부"),eQuote(basis,BASIS)),List.of(chunkRow(chunk,CHUNK_TEXT)),List.of(pc(chunk,CHUNK_TEXT),pc(basis,BASIS)));
    }
    record PriorCite(int start,String quote) {}
    static PriorCite pc(int start,String quote) {return new PriorCite(start,quote);}
    static Map<String,Object> bQuote(String quote) {var q=new LinkedHashMap<String,Object>();q.put("ref","B2");q.put("source_ref",null);q.put("start",null);q.put("end",null);q.put("quote",quote);return q;}
    static Map<String,Object> eQuote(int start,String quote) {return Map.of("ref","E1","source_ref","R1","start",start,"end",start+cp(quote),"quote",quote);}
    static Map<String,Object> chunkRow(int start,String quote) {return Map.of("ref","E1","source_ref","R1","source_kind","EPISODE","start",start,"end",start+cp(quote),"quote",quote,"method","VECTOR","source_context",Map.of("episodeNo",100));}

    /** targetQuotes 위치(이번 원고에서 처음 나오는 곳)를 묻는 질문 하나를 근거 문장 quotes·조각 evidence로 CONFLICT 판정하고 오탈자까지 끝낸다. */
    Published decided(List<String> targetQuotes,List<Map<String,Object>> quotes,List<Map<String,Object>> evidence,List<PriorCite> priorCitations) throws Exception {
        var job=beginMultiLine();var report=reportId(job);
        var spans=new ArrayList<Map<String,Object>>();var spanCitations=new ArrayList<Map<String,Object>>();
        for(String quote:targetQuotes) {var span=span(at(targetText,quote),quote);spans.add(span);spanCitations.add(cite((int)span.get("start"),(int)span.get("end"),quote));}
        var d=draft("local-1",spans);d.put("subject_refs",List.of("S2"));d.put("property_or_rule","서식지");
        var planner=publication(job,"SETTING","PLANNER","TSEG1",batch("TSEG1",List.of(d)));planner.put("citations",spanCitations);internal(path(job,"/plans"),job,planner,200);
        var plan=publication(job,"SETTING","MERGED_PLAN","PLAN",Map.of("questions",List.of(Map.of("localRef","Q1","originalRefs",List.of("PLANNER:TSEG1:local-1"),"state",Map.of("draft",d)))));
        plan.put("citations",spanCitations);
        var check=internal(path(job,"/plans"),job,plan,200).path("checks").path(0);
        var decision=conflict(check,false);
        @SuppressWarnings("unchecked") var bundle=(Map<String,Object>)decision.get("evidence_bundle");
        bundle.put("question",d);
        var r=stored(get(report));var settings=new ArrayList<Object>();
        dataMapper.toValidationSettings(r.getBaselineEntries(),dataMapper.toSourceReferences(r)).forEach((ref,row)->{if("S2".equals(row.path("subject_ref").asText()))settings.add(Map.of("entry_ref",ref,"subject_ref","S2"));});
        bundle.put("settings",settings);bundle.put("evidence",evidence);
        decision.put("target_spans",spans);decision.put("evidence_refs",List.of("T1","E1"));decision.put("basis_quotes",quotes);
        var command=unit(job,"SETTING","Q1","CONFLICT",decision);
        command.put("checkId",check.path("checkId").asText());command.put("expectedCheckRevision",check.path("revision").asLong());command.put("checkInputDigest",check.path("checkInputDigest").asText());
        var citations=new ArrayList<Object>(spanCitations);citations.add(cite(0,8,"처음 합성 원고"));for(var c:priorCitations)citations.add(prior(c.start(),c.quote()));command.put("citations",citations);
        internal(path(job,"/units"),job,command,200);
        typos(job);assertThat(finish(job,"done")).isEqualTo("COMPLETED");
        return new Published(job,report);
    }

    JsonNode context(UUID report,JsonNode finding,String query,String bearer,int status) throws Exception {
        return read("/"+report+"/findings/"+finding.path("findingId").asText()+"/source-context?"+query,bearer,status);
    }
    static List<String> lines(JsonNode context) {var out=new ArrayList<String>();context.path("lines").forEach(l->out.add(l.path("text").asText()));return out;}
    JsonNode typo(UUID report,String quote) throws Exception {
        for(var row:findings(report,"?branch=TYPO").path("content"))if(quote.equals(row.path("summary").path("spans").path(0).path("quote").asText()))return row;
        throw new AssertionError("no typo "+quote);
    }

    @Test @DisplayName("상세 blocks는 이번 원고 → 저장 설정값 → 이전 회차 근거 순서이고 원문 블록은 인용 위아래 2줄을 함께 준다")
    void detailBlocksCarryInitialContext() throws Exception {
        var p=decided();var finding=only(p.report(),"SETTING");
        clearInvocations(storage);
        var detail=detail(p.report(),finding);
        var blocks=detail.path("blocks");
        assertThat(blocks.size()).isEqualTo(3);
        var targetBlock=blocks.path(0);int mid=at(TARGET,MID_QUOTE);
        assertThat(targetBlock.path("kind").asText()).isEqualTo("TARGET");
        assertThat(targetBlock.path("label").asText()).isEqualTo("101화 · "+(mid+1)+"~"+(mid+cp(MID_QUOTE))+"자");
        assertThat(targetBlock.path("quote").asText()).isEqualTo(MID_QUOTE);
        assertThat(targetBlock.path("contextStatus").asText()).isEqualTo("READY");
        assertThat(lines(targetBlock.path("context"))).containsExactly("둘째 줄 합성","셋째 줄 합성","넷째 줄 대상 문장","다섯째 줄 합성","여섯째 줄 합성");
        assertThat(targetBlock.path("context").path("hasMoreBefore").asBoolean()).isTrue();
        assertThat(targetBlock.path("context").path("hasMoreAfter").asBoolean()).isFalse();
        assertThat(targetBlock.path("context").path("totalCodePoints").asInt()).isEqualTo(cp(TARGET));
        // CRLF 원문도 줄 글에 줄 끝 문자가 없고, 위치는 원문 전체 기준 code point다.
        for(var line:targetBlock.path("context").path("lines")) {
            assertThat(line.path("text").asText()).doesNotContain("\r","\n");
            assertThat(TARGET.substring(TARGET.offsetByCodePoints(0,line.path("start").asInt()),TARGET.offsetByCodePoints(0,line.path("end").asInt()))).isEqualTo(line.path("text").asText());
        }
        var setting=blocks.path(1);
        assertThat(setting.path("kind").asText()).isEqualTo("SETTING");
        assertThat(setting.path("label").asText()).isEqualTo("설정 · 합성 종족 · 서식지");
        assertThat(setting.path("quote").asText()).isEqualTo("북부");
        assertThat(setting.path("settingValue").asText()).isEqualTo("북부");
        assertThat(setting.path("context").isNull()).isTrue();assertThat(setting.path("contextStatus").isNull()).isTrue();assertThat(setting.path("sourceRef").isNull()).isTrue();
        var episode=blocks.path(2);int basis=at(PRIOR,BASIS);
        assertThat(episode.path("kind").asText()).isEqualTo("EPISODE");
        assertThat(episode.path("label").asText()).isEqualTo("100화 · "+(basis+1)+"~"+(basis+cp(BASIS))+"자");
        assertThat(episode.path("episodeNo").asInt()).isEqualTo(100);
        assertThat(episode.path("sourceRef").asText()).isEqualTo("R1");
        assertThat(lines(episode.path("context"))).containsExactly("가 줄","나 줄","다 줄 근거 문장이 있다","라 줄","마 줄");
        assertThat(episode.path("context").path("hasMoreBefore").asBoolean()).isTrue();
        assertThat(episode.path("context").path("hasMoreAfter").asBoolean()).isTrue();
        for(var block:blocks)assertThat(block.path("label").asText()).doesNotContainPattern("(?<![A-Za-z0-9])(T1|R[0-9]+|E[0-9]+|B[0-9]+)(?![0-9])");
        // 출처마다 저장소 전문 GET은 한 번이다(이번 원고·100화).
        verify(storage,times(1)).getText(target.getContentS3Key());
        verify(storage,times(1)).getText(prior.getContentS3Key());
        verifyNoMoreInteractions(storage);
        noExtraction();
    }

    @Test @DisplayName("오탈자 상세는 오탈자 위치 하나를 이번 원고 블록으로 주고 앞뒤 줄은 같은 규칙이다")
    void typoBlockHasContext() throws Exception {
        var p=decided();var row=typo(p.report(),"원고");
        var blocks=detail(p.report(),row).path("blocks");
        assertThat(blocks.size()).isEqualTo(1);
        assertThat(blocks.path(0).path("kind").asText()).isEqualTo("TARGET");
        assertThat(blocks.path(0).path("label").asText()).isEqualTo("101화 · 7~8자");
        assertThat(blocks.path(0).path("quote").asText()).isEqualTo("원고");
        assertThat(lines(blocks.path(0).path("context"))).containsExactly("처음 합성 원고","둘째 줄 합성","셋째 줄 합성");
        assertThat(blocks.path(0).path("context").path("hasMoreBefore").asBoolean()).isFalse();
    }

    @Test @DisplayName("위로·아래로 더 보기는 창 경계에서 새 줄만 주고 원문 처음·끝에서 멈추며 아무것도 저장하지 않는다")
    void expandsUpAndDownFromWindowEdges() throws Exception {
        var p=decided();var finding=only(p.report(),"SETTING");
        var episode=detail(p.report(),finding).path("blocks").path(2).path("context");
        long version=get(p.report()).path("reportVersion").asLong();
        var up=context(p.report(),finding,"sourceRef=R1&anchorStart="+episode.path("start").asInt()+"&anchorEnd="+episode.path("start").asInt()+"&before=5",token,200);
        assertThat(lines(up)).containsExactly("합성 앞 원고");
        assertThat(up.path("hasMoreBefore").asBoolean()).isFalse();assertThat(up.path("hasMoreAfter").asBoolean()).isTrue();
        assertThat(up.path("sourceRef").asText()).isEqualTo("R1");assertThat(up.path("totalCodePoints").asInt()).isEqualTo(cp(PRIOR));
        var down=context(p.report(),finding,"sourceRef=R1&anchorStart="+episode.path("end").asInt()+"&anchorEnd="+episode.path("end").asInt()+"&after=5",token,200);
        assertThat(lines(down)).containsExactly("바 줄");
        assertThat(down.path("hasMoreAfter").asBoolean()).isFalse();
        // 이번 원고도 같은 경로로 읽는다(위쪽 끝까지).
        var targetContext=detail(p.report(),finding).path("blocks").path(0).path("context");
        var targetUp=context(p.report(),finding,"sourceRef=T1&anchorStart="+targetContext.path("start").asInt()+"&anchorEnd="+targetContext.path("start").asInt()+"&before=30",token,200);
        assertThat(lines(targetUp)).containsExactly("처음 합성 원고");
        assertThat(get(p.report()).path("reportVersion").asLong()).isEqualTo(version);
        noExtraction();
    }

    @Test @DisplayName("작품 소유자만 읽고 항목이 인용하지 않은 출처·원문 밖 위치·줄 수와 anchor 상한 초과는 거절한다")
    void restrictsOwnerSourcesAndSizes() throws Exception {
        var p=decided();var finding=only(p.report(),"SETTING");var typo=typo(p.report(),"원고");
        var other=Member.register("other-"+UUID.randomUUID()+"@example.invalid","test-only","01099998888","다른 작가");em.persist(other);em.flush();
        assertThat(code(context(p.report(),finding,"sourceRef=T1&anchorStart=0&anchorEnd=2&before=2",tokens.generateAccessToken(other),404))).isEqualTo("WORK_NOT_FOUND");
        // 오탈자 항목은 이번 원고만 인용하므로 고정 manifest에 있는 100화라도 읽을 수 없다. 없는 출처도 같다.
        assertThat(code(context(p.report(),typo,"sourceRef=R1&anchorStart=0&anchorEnd=2",token,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(code(context(p.report(),finding,"sourceRef=R9&anchorStart=0&anchorEnd=2",token,400))).isEqualTo("REQUEST_VALIDATION_FAILED");
        for(String bad:List.of("anchorStart=0&anchorEnd=2&before=31","anchorStart=0&anchorEnd=2&after=31","anchorStart=-1&anchorEnd=2","anchorStart=3&anchorEnd=2",
                "anchorStart=0&anchorEnd=1001","anchorStart=0&anchorEnd="+(cp(TARGET)+1),"anchorStart=0&anchorEnd=2&before=-1"))
            assertThat(code(context(p.report(),finding,"sourceRef=T1&"+bad,token,400))).as(bad).isEqualTo("REQUEST_VALIDATION_FAILED");
        var unknown=UUID.randomUUID();
        assertThat(code(read("/"+p.report()+"/findings/"+unknown+"/source-context?sourceRef=T1&anchorStart=0&anchorEnd=2",token,404))).isEqualTo("VALIDATION_FINDING_NOT_FOUND");
    }

    /** 넷째·다섯째 줄의 두 위치와 100화 넷째·다섯째 줄의 두 근거 문장(근거 문장은 최대 3개라 설정값은 하나): 이웃해 각각 한 블록이 된다. */
    Published adjacent() throws Exception {
        int line4=at(PRIOR,BASIS),line5=at(PRIOR,"라 줄");
        return decided(List.of(MID_QUOTE,"다섯째 줄"),List.of(bQuote("북부"),eQuote(line5,"라 줄"),eQuote(line4,BASIS)),
                List.of(chunkRow(0,PRIOR)),List.of(pc(0,PRIOR),pc(line4,BASIS),pc(line5,"라 줄")));
    }
    static List<String> quotes(JsonNode block) {var out=new ArrayList<String>();block.path("quotes").forEach(q->out.add(q.path("quote").asText()));return out;}

    @Test @DisplayName("앞뒤 줄이 겹치는 이번 원고 두 위치·같은 회차 두 근거는 각각 한 블록으로 합치고 같은 줄을 두 번 보이지 않는다")
    void adjacentQuotesMergeIntoOneBlock() throws Exception {
        var p=adjacent();var finding=only(p.report(),"SETTING");
        clearInvocations(storage);
        var blocks=detail(p.report(),finding).path("blocks");
        assertThat(blocks.size()).isEqualTo(3);
        var targetBlock=blocks.path(0);int mid=at(TARGET,MID_QUOTE),fifth=at(TARGET,"다섯째 줄");
        assertThat(targetBlock.path("kind").asText()).isEqualTo("TARGET");
        assertThat(targetBlock.path("label").asText()).isEqualTo("101화 · "+(mid+1)+"~"+(fifth+cp("다섯째 줄"))+"자");
        assertThat(quotes(targetBlock)).containsExactly(MID_QUOTE,"다섯째 줄");
        assertThat(targetBlock.path("quote").asText()).isEqualTo(MID_QUOTE);
        assertThat(targetBlock.path("start").asInt()).isEqualTo(mid);assertThat(targetBlock.path("end").asInt()).isEqualTo(fifth+cp("다섯째 줄"));
        assertThat(targetBlock.path("quotes").path(1).path("start").asInt()).isEqualTo(fifth);
        assertThat(lines(targetBlock.path("context"))).containsExactly("둘째 줄 합성","셋째 줄 합성","넷째 줄 대상 문장","다섯째 줄 합성","여섯째 줄 합성").doesNotHaveDuplicates();
        var setting=blocks.path(1);
        assertThat(setting.path("kind").asText()).isEqualTo("SETTING");assertThat(quotes(setting)).containsExactly("북부");
        assertThat(setting.path("quotes").path(0).path("start").isNull()).isTrue();
        var episode=blocks.path(2);int line4=at(PRIOR,BASIS),line5=at(PRIOR,"라 줄");
        assertThat(episode.path("kind").asText()).isEqualTo("EPISODE");
        assertThat(quotes(episode)).as("source order, not basis order").containsExactly(BASIS,"라 줄");
        assertThat(episode.path("label").asText()).isEqualTo("100화 · "+(line4+1)+"~"+(line5+cp("라 줄"))+"자");
        assertThat(lines(episode.path("context"))).containsExactly("가 줄","나 줄","다 줄 근거 문장이 있다","라 줄","마 줄","바 줄");
        assertThat(episode.path("context").path("hasMoreAfter").asBoolean()).isFalse();
        // 합친 블록도 출처마다 저장소 전문 GET은 한 번이다.
        verify(storage,times(1)).getText(target.getContentS3Key());verify(storage,times(1)).getText(prior.getContentS3Key());
        // 더 보기는 합친 창의 경계에서 그대로 이어진다.
        var context=targetBlock.path("context");
        var up=context(p.report(),finding,"sourceRef=T1&anchorStart="+context.path("start").asInt()+"&anchorEnd="+context.path("start").asInt()+"&before=5",token,200);
        assertThat(lines(up)).containsExactly("처음 합성 원고");assertThat(up.path("hasMoreBefore").asBoolean()).isFalse();
        var episodeUp=context(p.report(),finding,"sourceRef=R1&anchorStart="+episode.path("context").path("start").asInt()+"&anchorEnd="+episode.path("context").path("start").asInt()+"&before=5",token,200);
        assertThat(lines(episodeUp)).containsExactly("합성 앞 원고");
        // 인용 하나인 블록도 quotes에 한 항목을 준다(오탈자).
        var typo=detail(p.report(),typo(p.report(),"원고")).path("blocks").path(0);
        assertThat(quotes(typo)).containsExactly("원고");assertThat(typo.path("quotes").path(0).path("start").asInt()).isEqualTo(6);
        noExtraction();
    }

    @Test @DisplayName("같은 저장 설정값 안의 두 근거 문장은 값 하나에 두 곳을 강조한 블록 하나다")
    void quotesFromOneSettingValueMerge() throws Exception {
        var p=decided(List.of(MID_QUOTE),List.of(bQuote("부"),bQuote("북")),List.of(chunkRow(at(PRIOR,CHUNK_TEXT),CHUNK_TEXT)),List.of(pc(at(PRIOR,CHUNK_TEXT),CHUNK_TEXT)));
        var blocks=detail(p.report(),only(p.report(),"SETTING")).path("blocks");
        assertThat(blocks.size()).isEqualTo(2);
        var setting=blocks.path(1);
        assertThat(setting.path("kind").asText()).isEqualTo("SETTING");assertThat(setting.path("label").asText()).isEqualTo("설정 · 합성 종족 · 서식지");
        assertThat(setting.path("settingValue").asText()).isEqualTo("북부");
        assertThat(quotes(setting)).as("position order inside the value").containsExactly("북","부");
        assertThat(setting.path("quote").asText()).isEqualTo("북");
        for(var q:setting.path("quotes"))assertThat(q.path("start").isNull()&&q.path("end").isNull()).isTrue();
        // 판정 근거 문장 목록(basisQuotes)은 합치지 않는다.
        assertThat(detail(p.report(),only(p.report(),"SETTING")).path("basisQuotes").size()).isEqualTo(2);
    }

    @Test @DisplayName("사이에 글 줄이 있어 앞뒤 줄이 닿지 않는 위치는 블록을 나눈다")
    void distantQuotesStaySeparate() throws Exception {
        targetText=LONG_TARGET;
        int last=at(PRIOR,"바 줄");
        var p=decided(List.of("둘째 줄","열째 줄 먼 문장"),List.of(eQuote(0,"합성 앞 원고"),eQuote(last,"바 줄")),
                List.of(chunkRow(0,PRIOR)),List.of(pc(0,PRIOR),pc(0,"합성 앞 원고"),pc(last,"바 줄")));
        var blocks=detail(p.report(),only(p.report(),"SETTING")).path("blocks");
        assertThat(blocks.size()).isEqualTo(4);
        assertThat(quotes(blocks.path(0))).containsExactly("둘째 줄");assertThat(quotes(blocks.path(1))).containsExactly("열째 줄 먼 문장");
        assertThat(lines(blocks.path(0).path("context"))).containsExactly("처음 합성 원고","둘째 줄","셋째 줄","넷째 줄");
        assertThat(lines(blocks.path(1).path("context"))).containsExactly("여덟째 줄","아홉째 줄","열째 줄 먼 문장","열한째 줄","열두째 줄");
        assertThat(blocks.path(2).path("kind").asText()).isEqualTo("EPISODE");assertThat(quotes(blocks.path(2))).containsExactly("합성 앞 원고");
        assertThat(blocks.path(3).path("kind").asText()).isEqualTo("EPISODE");assertThat(quotes(blocks.path(3))).containsExactly("바 줄");
        assertThat(blocks.path(2).path("label").asText()).isEqualTo("100화 · 1~"+cp("합성 앞 원고")+"자");
    }

    @Test @DisplayName("원문을 읽지 못하면 이웃한 위치도 합치지 않고 위치마다 블록과 상태를 준다")
    void unreadableSourcesAreNotMerged() throws Exception {
        var p=adjacent();var finding=only(p.report(),"SETTING");
        doThrow(new IllegalStateException("synthetic storage outage")).when(storage).getText(anyString());
        var blocks=detail(p.report(),finding).path("blocks");
        assertThat(blocks.size()).as("2 target + setting + 2 episode").isEqualTo(5);
        for(int i:List.of(0,1,3,4)) {assertThat(blocks.path(i).path("contextStatus").asText()).isEqualTo("SOURCE_UNREADABLE");assertThat(blocks.path(i).path("quotes").size()).isEqualTo(1);assertThat(blocks.path(i).path("context").isNull()).isTrue();}
        assertThat(quotes(blocks.path(0))).containsExactly(MID_QUOTE);assertThat(quotes(blocks.path(1))).containsExactly("다섯째 줄");
        assertThat(blocks.path(2).path("kind").asText()).isEqualTo("SETTING");assertThat(quotes(blocks.path(2))).containsExactly("북부");
        assertThat(quotes(blocks.path(3))).containsExactly(BASIS);assertThat(quotes(blocks.path(4))).containsExactly("라 줄");
    }

    @Test @DisplayName("원문 hash가 고정값과 다르거나 회차가 보관되면 앞뒤 줄을 비우고 더 보기는 409로 사유를 준다")
    void changedOrPurgedSourceIsNotShown() throws Exception {
        var p=decided();var finding=only(p.report(),"SETTING");
        doAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?TARGET:PRIOR+"\n고친 줄").when(storage).getText(anyString());
        var blocks=detail(p.report(),finding).path("blocks");
        assertThat(blocks.path(0).path("contextStatus").asText()).isEqualTo("READY");
        assertThat(blocks.path(2).path("contextStatus").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(blocks.path(2).path("context").isNull()).isTrue();
        assertThat(blocks.path(2).path("quote").asText()).isEqualTo(BASIS);
        var error=context(p.report(),finding,"sourceRef=R1&anchorStart=0&anchorEnd=2&before=2",token,409);
        assertThat(code(error)).isEqualTo("VALIDATION_SOURCE_CONTEXT_UNAVAILABLE");
        assertThat(error.path("context").path("reason").asText()).isEqualTo("SOURCE_CHANGED");
        // 저장소가 예외를 던지면 읽지 못함으로 구분한다.
        doThrow(new IllegalStateException("synthetic storage outage")).when(storage).getText(anyString());
        assertThat(detail(p.report(),finding).path("blocks").path(0).path("contextStatus").asText()).isEqualTo("SOURCE_UNREADABLE");
        assertThat(context(p.report(),finding,"sourceRef=T1&anchorStart=0&anchorEnd=2",token,409).path("context").path("reason").asText()).isEqualTo("SOURCE_UNREADABLE");
        // 보관된 이전 회차는 파기 대상이라 읽지 않는다.
        doAnswer(c->c.getArgument(0).equals(target.getContentS3Key())?TARGET:PRIOR).when(storage).getText(anyString());
        em.createNativeQuery("update episodes set status='ARCHIVED' where id=:id").setParameter("id",prior.getId()).executeUpdate();em.clear();
        assertThat(detail(p.report(),finding).path("blocks").path(2).path("contextStatus").asText()).isEqualTo("SOURCE_PURGED");
    }
}
