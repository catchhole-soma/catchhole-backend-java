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
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeDetectionRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeTitleUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeUploadRequest;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeDetectionResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeSummaryResponse;
import org.monitoring.catchholebackend.domain.episode.dto.response.EpisodeUploadResponse;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PutMapping;
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
            operationId = "getEpisodes",
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
            operationId = "uploadEpisodes",
            summary = "회차 원고 업로드",
            description = "로그인한 사용자가 본인 작품에 단일 회차, 단일 파일 다회차, 여러 파일 다회차 방식으로 원고를 업로드합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 업로드 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패 또는 업로드 파일 오류"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 회차 번호")
    })
    public CommonResponse<EpisodeUploadResponse> uploadEpisodes(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable UUID workId,
            @Valid @RequestPart("metadata") EpisodeUploadRequest uploadRequest,
            @RequestPart("episodeFiles") List<MultipartFile> sourceEpisodeFiles,
            @RequestPart(value = "settingBookFile", required = false) MultipartFile attachedSettingBookFile
    ) {
        return CommonResponse.success(
                "회차 원고가 업로드되었습니다.",
                episodeService.uploadEpisodes(
                        memberPrincipal.memberId(),
                        workId,
                        uploadRequest,
                        sourceEpisodeFiles,
                        attachedSettingBookFile
                )
        );
    }

    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "detectEpisodes",
            summary = "회차 원고 사전 감지",
            description = "영구 저장 없이 원고 파일의 명시적인 회차 제목 행과 회차 번호·제목·본문 경계를 감지합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 감지 성공"),
            @ApiResponse(responseCode = "400", description = "파일 검증 또는 회차 감지 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<EpisodeDetectionResponse> detectEpisodes(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @PathVariable UUID workId,
            @Valid @RequestPart("metadata") EpisodeDetectionRequest detectionRequest,
            @RequestPart("episodeFiles") List<MultipartFile> sourceEpisodeFiles
    ) {
        return CommonResponse.success(
                episodeService.detectEpisodes(
                        memberPrincipal.memberId(),
                        workId,
                        detectionRequest,
                        sourceEpisodeFiles
                )
        );
    }

    @GetMapping("/{episodeId}")
    @Operation(
            operationId = "getEpisode",
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

    @PatchMapping("/{episodeId}")
    @Operation(
            operationId = "updateEpisode",
            summary = "회차 원문 수정",
            description = "로그인한 사용자가 본인 작품에 등록한 회차 번호, 제목, 원문을 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 회차를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 회차 번호")
    })
    public CommonResponse<EpisodeResponse> updateEpisode(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID episodeId,
            @Valid @RequestBody EpisodeUpdateRequest request
    ) {
        return CommonResponse.success(
                "회차 원고가 수정되었습니다.",
                episodeService.updateEpisode(member.memberId(), workId, episodeId, request)
        );
    }

    @PatchMapping("/{episodeId}/title")
    @Operation(
            operationId = "updateEpisodeTitle",
            summary = "회차 제목 수정",
            description = "원문과 분석 상태를 유지한 채 회차 제목만 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 제목 수정 성공"),
            @ApiResponse(responseCode = "400", description = "제목 길이 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 회차를 찾을 수 없음")
    })
    public CommonResponse<EpisodeSummaryResponse> updateEpisodeTitle(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID episodeId,
            @Valid @RequestBody EpisodeTitleUpdateRequest request
    ) {
        return CommonResponse.success(
                "회차 제목이 수정되었습니다.",
                episodeService.updateEpisodeTitle(member.memberId(), workId, episodeId, request)
        );
    }

    @PutMapping(value = "/{episodeId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "replaceEpisodeFile",
            summary = "회차 원문 파일 변경",
            description = "회차 번호와 제목을 유지하고 새 TXT 또는 DOCX 원본으로 교체합니다. 자동 분석은 시작하지 않습니다."
    )
    public CommonResponse<EpisodeSummaryResponse> replaceEpisodeFile(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID episodeId,
            @RequestPart("file") MultipartFile file
    ) {
        return CommonResponse.success(
                "회차 원문 파일이 변경되었습니다.",
                episodeService.replaceEpisodeFile(member.memberId(), workId, episodeId, file)
        );
    }

    @DeleteMapping("/{episodeId}")
    @Operation(
            operationId = "deleteEpisode",
            summary = "회차 원문 영구 삭제",
            description = "로그인한 사용자가 본인 작품에 등록한 회차를 보관 상태로 전환해 활성 목록에서 숨깁니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 회차를 찾을 수 없음")
    })
    public CommonResponse<Void> deleteEpisode(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID episodeId
    ) {
        episodeService.deleteEpisode(member.memberId(), workId, episodeId);
        return CommonResponse.success("회차 원고가 삭제되었습니다.", null);
    }
}
