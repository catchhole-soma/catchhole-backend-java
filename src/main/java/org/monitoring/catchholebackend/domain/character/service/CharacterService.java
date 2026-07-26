package org.monitoring.catchholebackend.domain.character.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterArchiveResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterSummaryResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface CharacterService {

    /**
     * 작품 소유권을 확인한 뒤 보관되지 않은 캐릭터 카드 목록을 페이지 단위로 조회한다.
     * 페이지 사이 순서를 고정하기 위해 생성 시각과 ID를 모두 내림차순으로 정렬한다.
     * 첫 등장 회차 ID가 유효하면 카드에 표시할 회차 번호를 함께 응답한다.
     * 첫 등장 회차가 없거나 현재 작품에서 유효하지 않으면 해당 값만 null로 응답한다.
     */
    PageResponse<CharacterSummaryResponse> getCharacters(Long memberId, UUID workId, int page, int size);

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
