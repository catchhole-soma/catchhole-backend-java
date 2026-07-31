package org.monitoring.catchholebackend.domain.character.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactSearchResponse;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchScope;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSearchType;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface CharacterFactService {

    /**
     * 작품 소유권을 확인한 뒤 ACTIVE 캐릭터의 검색 가능한 Fact를 페이지 조회한다.
     * 검색어는 trim 후 factKey와 factValue에 대소문자 구분 없는 literal 부분 일치로 적용한다.
     */
    PageResponse<CharacterFactSearchResponse> searchCharacterFacts(
            Long memberId,
            UUID workId,
            String query,
            CharacterFactSearchType factType,
            CharacterFactSearchScope scope,
            int page,
            int size
    );

    /**
     * 작품 소유권과 ACTIVE 캐릭터 소속을 확인한 뒤 Fact와 저장된 후보 근거 인용문을 조회한다.
     */
    CharacterFactDetailResponse getCharacterFact(Long memberId, UUID workId, UUID characterFactId);

    /**
     * 본인 작품의 CharacterFact와 연결된 설정 후보를 따라가 분석 당시 회차 원문과 근거 범위를 조회한다.
     * 수동 Fact 또는 원문 조회 실패는 오류 대신 nullable 원문과 빈 근거 목록으로 표현한다.
     */
    CharacterFactEvidenceResponse getEvidence(Long memberId, UUID workId, UUID characterFactId);
}
