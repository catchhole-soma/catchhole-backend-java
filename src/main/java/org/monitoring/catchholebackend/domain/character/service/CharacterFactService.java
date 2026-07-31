package org.monitoring.catchholebackend.domain.character.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
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
}
