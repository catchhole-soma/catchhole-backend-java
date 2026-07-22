package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;

public interface CharacterService {

    /**
     * 작품 소유권을 확인한 뒤 보관되지 않은 캐릭터 카드 목록을 최신 생성순으로 조회한다.
     * 첫 등장 회차 ID가 유효하면 카드에 표시할 회차 번호를 함께 응답한다.
     */
    List<CharacterSummaryResponse> getCharacters(Long memberId, UUID workId);

    /**
     * 작품 소유권과 캐릭터 소속을 확인한 뒤 활성 캐릭터의 기본 정보와 현재 설정 전체를 조회한다.
     * 과거 Fact와 TIME Fact는 화면용 설정 목록에서 제외한다.
     */
    CharacterDetailResponse getCharacter(Long memberId, UUID workId, UUID characterId);

    /**
     * 활성 캐릭터를 잠금 조회한 뒤 기본 정보와 현재 설정 전체를 한 트랜잭션에서 수정한다.
     * 변경된 설정은 수동 정정 Fact로 추가하고 기존 current Fact는 historical로 전환한다.
     */
    CharacterDetailResponse updateCharacter(
            Long memberId,
            UUID workId,
            UUID characterId,
            CharacterUpdateRequest request
    );

    /**
     * 화면의 삭제 요청을 처리하되 캐릭터와 설정 이력은 삭제하지 않고 ARCHIVED 상태로 전환한다.
     */
    CharacterArchiveResponse archiveCharacter(Long memberId, UUID workId, UUID characterId);
}
