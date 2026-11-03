package org.monitoring.catchholebackend.domain.validation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.*;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.validation.type.*;

@Schema(description = "발견 항목 상세. 내부 evidence_bundle·catalogue·호출 기록은 포함하지 않습니다.")
public record ValidationFindingDetailResponse(UUID findingId, ValidationBranch branch, String unitRef, UUID checkId, Long checkRevision,
        @Schema(description = "질문의 현재 revision. 오탈자는 null") Long currentCheckRevision,
        String outcome,
        @Schema(description = "현재 revision이 아닌 이전 항목이면 true. 판단 저장은 거절됩니다.") boolean stale,
        boolean changed,
        @Schema(description = "판정 사유·영향·원문 위치·제안 등 공개 판정 내용") Map<String, Object> payload,
        @Schema(description = "저장 때 검증한 인용 근거") List<Evidence> evidence,
        ValidationFindingSummaryResponse.Judgment judgment,
        List<ValidationJudgmentValue> allowedJudgments,
        @Schema(description = "이 항목과 같은 질문 이전 revision 항목의 판단 이력") List<History> judgmentHistory,
        @Schema(description = "설정 항목의 이전 revision과 당시 판단·변경 사유") List<PriorRevision> priorRevisions,
        @Schema(description = "#314 B2. 목록과 같은 대상. 오탈자·대상 없는 질문은 null", nullable = true) ValidationFindingSummaryResponse.Subject subject,
        @Schema(description = "#314 B2. 목록과 같은 설정 표시명", example = "나이", nullable = true) String settingName,
        @Schema(description = "#314 B2. 목록과 같은 설정 키", example = "AGE:age", nullable = true) String settingKey,
        @Schema(description = "#314 B2. 이 질문이 비교한 고정 기준 항목 중 정정(POST /{reportId}/corrections)에 그대로 쓸 수 있는 항목. 실제 캐릭터·세계관에 연결된 항목만 기준 순서로 담습니다. 오탈자는 빈 배열입니다.")
        List<Basis> basis,
        @Schema(description = "오류 리포트 가독성. 이 질문이 확인한 항목(판정 질문의 property_or_rule). 내부 참조처럼 보이면 null이며 오탈자는 null", example = "골강도와 방어력의 관계", nullable = true) String topic,
        @Schema(description = "판정 작성 제목(2026-11-03). 판정이 쓴 작가용 제목 한 줄(예: '데스핀드와 키메라 울프의 정수 등급이 기존 설정과 달라요'). 이전 Worker 결과·제목을 쓰지 않은 판정·내부 참조처럼 보이는 값과 오탈자는 null이며, 이때 화면은 대상·확인 항목으로 제목을 만듭니다.", example = "합성 인물의 왼손 상태가 기존 설정과 달라요", nullable = true) String headline,
        @Schema(description = "오류 리포트 가독성. 이번 원고(T1)의 회차 번호", example = "31") Integer targetEpisodeNo,
        @Schema(description = "오류 리포트 가독성. 판정이 근거로 그대로 옮긴 문장 0~3개(저장 설정값·이전 회차·설정집). 서버가 고정 기준·검증한 원문으로 만든 작가용 label을 함께 준다. 근거 문장이 없거나 이전 Worker 결과면 빈 배열")
        List<BasisQuote> basisQuotes,
        @Schema(description = "오류 리포트 문맥 블록(2026-11-03). 상세 비교 영역을 위에서부터 쌓는 블록: 이번 원고 위치(TARGET, 원고 위치 순·오탈자는 그 위치 하나) → 판정이 옮긴 저장 설정값(SETTING, basis_quotes 순서) → 판정이 옮긴 이전 원문 문장(EPISODE 회차 오름차순, 그다음 SETTING_BOOK). 원문 블록은 인용 문장 위아래 2줄(공백 줄 제외)을 context로 함께 주며 더 보기는 GET .../source-context로 읽습니다. 같은 출처의 인용끼리 처음 앞뒤 줄이 겹치거나 맞닿으면 한 블록으로 합치고 강조할 문장은 quotes에 모두 담습니다(같은 줄이 두 번 보이지 않습니다). 근거 문장이 없는 판정은 TARGET만 있습니다.")
        List<Block> blocks) {
    @Schema(name = "ValidationFindingBlock", description = "상세 비교 영역의 블록 하나. 머리말은 종류(이번 원고·기준·근거)와 label을 이어 씁니다.")
    public record Block(@Schema(description = "블록 종류") ValidationFindingBlockKind kind,
            @Schema(description = "작가용 위치 표기. TARGET·EPISODE는 'N화 · 시작~끝자'(1부터 센 code point), SETTING_BOOK은 '설정집 · 제목 · 시작~끝자', SETTING은 '설정 · 대상 · 설정명'", example = "31화 · 2201~2241자") String label,
            @Schema(description = "강조할 첫 문장 그대로(이번 원고 인용·오탈자 원문·근거 문장). 합친 블록은 quotes의 첫 항목과 같습니다", example = "합성 인용 문장") String quote,
            @Schema(description = "이 블록에서 강조할 문장 전부(원문 위치 순, 1개 이상). 인용 하나인 블록은 (quote, start, end) 한 항목입니다") List<BlockQuote> quotes,
            @Schema(description = "원문 블록의 출처 참조(화면 표시용 아님, source-context 요청에 그대로 보냄). SETTING은 null", nullable = true) String sourceRef,
            @Schema(description = "강조 문장들을 덮는 원문 code point 시작(quotes의 가장 작은 start). SETTING은 null", nullable = true) Integer start,
            @Schema(description = "강조 문장들을 덮는 원문 code point 끝(제외, quotes의 가장 큰 end). SETTING은 null", nullable = true) Integer end,
            @Schema(description = "회차 블록의 회차 번호", nullable = true) Integer episodeNo,
            @Schema(description = "SETTING: quote를 담은 저장 설정값 전체(값 하나에만 들어 있고 1,000자 이하일 때). 그 밖에는 null", nullable = true) String settingValue,
            @Schema(description = "원문 블록의 처음 앞뒤 줄. 읽지 못했거나 SETTING이면 null", nullable = true) ValidationSourceContextResponse context,
            @Schema(description = "원문 블록의 앞뒤 줄 읽기 결과. SETTING은 null", nullable = true) ValidationSourceContextStatus contextStatus) {}
    @Schema(name = "ValidationFindingBlockQuote", description = "블록 안에서 강조할 문장 하나. 원문 블록은 원문 전체 기준 Unicode code point [start,end), SETTING은 위치 없이 저장 설정값 안의 문장입니다.")
    public record BlockQuote(@Schema(description = "강조할 문장 그대로", example = "합성 인용 문장") String quote,
            @Schema(description = "원문 code point 시작. SETTING은 null", nullable = true) Integer start,
            @Schema(description = "원문 code point 끝(제외). SETTING은 null", nullable = true) Integer end) {}
    @Schema(name = "ValidationFindingEvidence", description = "저장 때 검증한 인용. Unicode code point 위치. 판정 근거 문장 자체의 인용은 basisQuotes로만 보여 주고 여기서는 뺍니다.")
    public record Evidence(String sourceRef, int start, int end, String quote,
            @Schema(description = "원문 종류. 이번 원고와 이전 회차는 EPISODE", example = "EPISODE") ValidationQuoteSourceKind sourceKind,
            @Schema(description = "회차 번호(이번 원고는 targetEpisodeNo). 설정집은 null", example = "18", nullable = true) Integer episodeNo,
            @Schema(description = "작가용 위치 표기. 회차는 'N화 · 시작~끝자'(1부터 센 code point), 설정집은 '설정집 · 제목 · 시작~끝자'", example = "18화 · 756~1613자") String label,
            @Schema(description = "판정이 실제로 근거로 쓴 조각(evidence_refs·근거 문장·불일치 참조)이면 true. 이번 원고와 검색만 하고 쓰지 않은 조각은 false") boolean usedByDecision,
            @Schema(description = "이 조각 안에 있는 판정 근거 문장의 위치(조각 quote 기준 code point [start,end))") List<Highlight> highlights) {}
    @Schema(name = "ValidationFindingHighlight", description = "인용 조각 안의 강조 구간. 조각 quote 기준 Unicode code point [start,end)")
    public record Highlight(int start, int end) {}
    @Schema(name = "ValidationFindingBasisQuote", description = "판정이 근거로 그대로 옮긴 문장. 저장 설정값은 고정 기준 행의 값, 원문은 서버가 hash 확인한 원문과 대조한 인용입니다.")
    public record BasisQuote(@Schema(description = "근거 종류") ValidationQuoteSourceKind kind,
            @Schema(description = "작가용 표기. 설정은 '설정 · 대상 · 설정명'(설정명을 하나로 정할 수 없으면 '설정 · 대상'), 회차는 'N화', 설정집은 '설정집 · 제목'", example = "설정 · 살점폭발 · 폭발 위력") String label,
            @Schema(description = "근거 문장 그대로", example = "폭발력 자체는 그리 세지 않다.") String quote,
            @Schema(description = "원문 인용이면 evidence의 sourceRef와 같은 출처 참조(화면 표시용 아님). 설정값은 null", nullable = true) String sourceRef,
            @Schema(description = "원문 인용의 code point 시작. 설정값은 null", nullable = true) Integer start,
            @Schema(description = "원문 인용의 code point 끝(제외). 설정값은 null", nullable = true) Integer end,
            @Schema(description = "회차 인용의 회차 번호", nullable = true) Integer episodeNo,
            @Schema(description = "설정값의 대상 이름", nullable = true) String subjectName,
            @Schema(description = "설정값의 설정명(캐릭터 표시명, 세계관 '범위 / 설정명')", nullable = true) String settingName,
            @Schema(description = "설정집 인용의 설정집 이름", nullable = true) String settingBookTitle) {}
    @Schema(name = "ValidationFindingJudgmentHistory", description = "판단 전이 이력. REVISION_SUPERSEDED는 changeReason을 함께 제공")
    public record History(UUID findingId, Long checkRevision, ValidationJudgmentValue fromJudgment, ValidationJudgmentValue toJudgment,
            ValidationJudgmentHistoryReason reason, @Schema(description = "REVISION_SUPERSEDED의 질문 변경 사유") String changeReason, LocalDateTime createdAt) {}
    @Schema(name = "ValidationFindingPriorRevision", description = "같은 설정 질문의 이전 revision 결과와 당시 판단")
    public record PriorRevision(long checkRevision, String outcome, String changeReason, UUID findingId, ValidationFindingSummaryResponse.Judgment judgment) {}
    @Schema(name = "ValidationFindingBasis", description = "#314 B2. 정정 요청에 그대로 옮기는 고정 기준 항목. entryRef→entryRef, entryHash→expectedEntryHash, targetId→character/worldSetting.targetId, currentVersion→expectedSnapshotVersion(캐릭터)·expectedVersion(세계관)입니다. baselineId·expectedBaselineRevision·expectedBaselineHash·expectedReportVersion은 GET /{reportId}의 baselineId·baselineRevision·baselineHash·reportVersion을 씁니다.")
    public record Basis(@Schema(description = "고정 기준 항목 참조", example = "B1") String entryRef,
            @Schema(description = "고정 기준 항목 자체의 hash(정정 요청 expectedEntryHash)", example = "0000000000000000000000000000000000000000000000000000000000000000") String entryHash,
            @Schema(description = "CHARACTER면 정정 요청 character, WORLD_SETTING이면 worldSetting을 채웁니다") ValidationSubjectType subjectType,
            @Schema(description = "실제 캐릭터·세계관 ID") UUID targetId,
            @Schema(description = "고정 기준 당시 대상 이름", example = "카일") String subjectName,
            @Schema(description = "캐릭터: finding의 settingKey가 이 항목의 slot을 가리킬 때의 factType. 그 밖에는 null이며 작가가 편집한 slot을 사용합니다.", nullable = true) CharacterFactType factType,
            @Schema(description = "캐릭터: 위와 같은 조건의 factKey", example = "age", nullable = true) String factKey,
            @Schema(description = "세계관: settingKey가 이 항목의 저장 경로를 가리킬 때의 범위명(WorldSettingPropertyUpdateRequest.currentScopeName). root 설정과 그 밖은 null", nullable = true) String scopeName,
            @Schema(description = "세계관: 같은 조건의 저장 설정명(currentSettingName). 캐릭터와 그 밖은 null", nullable = true) String settingName,
            @Schema(description = "고정 기준 당시 캐릭터 snapshotVersion 또는 세계관 version. 저장되지 않은 legacy 항목은 null", nullable = true) Long currentVersion) {}
}
