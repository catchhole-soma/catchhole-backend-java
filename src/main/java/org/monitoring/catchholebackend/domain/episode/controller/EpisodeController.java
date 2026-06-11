package org.monitoring.catchholebackend.domain.episode.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/episodes")
@Tag(name = "Episode", description = "로그인한 사용자의 작품별 회차 원고 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeService episodeService;

    @GetMapping
    @Operation(
            summary = "작품별 회차 목록 조회",
            description = "로그인한 사용자가 본인 작품에 등록한 회차 목록을 회차 번호 내림차순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<EpisodeSummaryResponse>> getEpisodes(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(episodeService.getEpisodes(member.memberId(), workId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "단일 회차 업로드",
            description = "로그인한 사용자가 본인 작품에 단일 회차 원고 파일을 업로드합니다. 다회차 파싱은 별도 작업에서 지원합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 업로드 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패 또는 업로드 파일 오류"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 회차 번호")
    })
    public CommonResponse<EpisodeUploadResponse> uploadEpisode(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestPart("data") EpisodeUploadRequest request,
            @RequestPart("episodeFiles") List<MultipartFile> episodeFiles,
            @RequestPart(value = "settingBookFile", required = false) MultipartFile settingBookFile
    ) {
        return CommonResponse.success(
                "회차 원고가 업로드되었습니다.",
                episodeService.uploadEpisodes(member.memberId(), workId, request, episodeFiles, settingBookFile)
        );
    }

    @GetMapping("/{episodeId}")
    @Operation(
            summary = "회차 상세 조회",
            description = "로그인한 사용자가 본인 작품에 등록한 특정 회차 원고 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 회차를 찾을 수 없음")
    })
    public CommonResponse<EpisodeResponse> getEpisode(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID episodeId
    ) {
        return CommonResponse.success(episodeService.getEpisode(member.memberId(), workId, episodeId));
    }
}
