package org.monitoring.catchholebackend.domain.relationship.controller;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Correction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Detail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Graph;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipGraphService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/relationship-graph")
@Tag(name = "Episode Relationship Graph", description = "선택 회차까지 드러난 인물 관계도")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "처리 성공"),
    @ApiResponse(responseCode = "400", description = "잘못된 입력"),
    @ApiResponse(responseCode = "401", description = "인증 필요"),
    @ApiResponse(responseCode = "404", description = "작품·회차·관계 없음"),
    @ApiResponse(responseCode = "409", description = "변경 버전 충돌")
})
public class RelationshipGraphController {
    private final RelationshipGraphService service;
    private final org.monitoring.catchholebackend.domain.relationship.service.RelationshipExplorerService explorer;

    @GetMapping("/explore/selection")
    @Operation(operationId = "exploreSelectedEpisodeRelationships", summary = "전체 인물 목록과 선택한 상대의 관계 탐색",
            description = "중심 관계 200개 이상은 상대 선택부터 시작합니다. 최대 20명·200개를 함께 선택하며 한 상대만 200개를 넘는 경우 페이지로 나눕니다.")
    public CommonResponse<org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.SelectionExploration> selection(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam(required = false) Integer episodeNo,
            @RequestParam(required = false) UUID focusNodeId,
            @Parameter(description = "함께 볼 상대의 인물 ID. 중복 없이 최대 20명") @RequestParam(required = false) List<UUID> counterpartNodeIds,
            @RequestParam(defaultValue = "0") int page) {
        return CommonResponse.success(explorer.exploreSelectedRelationships(member.memberId(), workId, episodeNo, focusNodeId, counterpartNodeIds, page));
    }

    @GetMapping("/explore")
    @Operation(operationId = "exploreEpisodeRelationships", summary = "관계 규모에 따른 개요 또는 중심 인물 탐색")
    public CommonResponse<org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.Exploration> explore(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam(required = false) Integer episodeNo,
            @RequestParam(required = false) UUID focusNodeId, @RequestParam(defaultValue = "0") int page) {
        return CommonResponse.success(explorer.exploreRelationships(member.memberId(), workId, episodeNo, focusNodeId, page));
    }

    @GetMapping("/people")
    @Operation(operationId = "searchEpisodeRelationshipPeople", summary = "선택 회차의 전체 관계 인물을 이름과 별칭으로 검색")
    public CommonResponse<org.monitoring.catchholebackend.global.common.response.PageResponse<org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.Person>> people(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @RequestParam(required = false) Integer episodeNo,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "생략하면 전체 인물, 지정하면 해당 인물과 직접 연결된 상대만 관계 수순으로 조회") @RequestParam(required = false) UUID focusNodeId) {
        return CommonResponse.success(focusNodeId == null
                ? explorer.searchRelationshipPeople(member.memberId(), workId, episodeNo, query, page, size)
                : explorer.searchRelationshipCounterparts(member.memberId(), workId, episodeNo, focusNodeId, query, page, size));
    }

    @GetMapping("/explore/edges/{edgeId}")
    @Operation(operationId = "getExploredRelationshipEdge", summary = "선택한 관계만 원문 근거 조회")
    public CommonResponse<Detail> exploredEdge(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID edgeId,
            @RequestParam(required = false) Integer episodeNo) {
        return CommonResponse.success(explorer.getRelationshipDetail(member.memberId(), workId, edgeId, episodeNo));
    }


    @GetMapping
    @Operation(
            operationId = "getEpisodeRelationshipGraph",
            summary = "회차별 인물 관계도 조회",
            description = "회차 생략은 최신 반영 회차입니다. 미래 인물 이름과 설명을 사용하지 않습니다.")
    public CommonResponse<Graph> getEpisodeRelationshipGraph(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestParam(required = false) Integer episodeNo) {
        return CommonResponse.success(
                service.getRelationshipGraph(member.memberId(), workId, episodeNo));
    }

    @GetMapping("/edges/{edgeId}")
    @Operation(
            operationId = "getEpisodeRelationshipEdge",
            summary = "회차 기준 관계 근거 조회",
            description = "선택 회차까지의 관계 설명과 원문 인용을 제공합니다.")
    public CommonResponse<Detail> getEpisodeRelationshipEdge(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID edgeId,
            @RequestParam(required = false) Integer episodeNo) {
        return CommonResponse.success(
                service.getRelationshipEdge(member.memberId(), workId, edgeId, episodeNo));
    }

    @PatchMapping("/edges/{edgeId}")
    @Operation(
            operationId = "updateEpisodeRelationshipEdge",
            summary = "관계 설명의 오류 수정",
            description = "서사상 관계 변화가 아닌 오류 정정으로 모든 회차 표시를 바로잡습니다.")
    public CommonResponse<Void> updateEpisodeRelationshipEdge(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID edgeId,
            @RequestBody Correction request) {
        service.correctRelationshipEdge(member.memberId(), workId, edgeId, request);
        return CommonResponse.success(null);
    }

    @DeleteMapping("/edges/{edgeId}")
    @Operation(
            operationId = "deleteEpisodeRelationshipEdge",
            summary = "잘못된 연결 삭제",
            description = "해당 연결을 모든 회차에서 숨기고 자동 분석이 되살리지 않도록 보호합니다.")
    public CommonResponse<Void> deleteEpisodeRelationshipEdge(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID edgeId,
            @RequestParam long expectedVersion) {
        service.deleteRelationshipEdge(member.memberId(), workId, edgeId, expectedVersion);
        return CommonResponse.success(null);
    }
}
