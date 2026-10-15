package org.monitoring.catchholebackend.domain.upload.controller;

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
import org.monitoring.catchholebackend.domain.upload.dto.request.SettingBookUpdateRequest;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookResponse;
import org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookSummaryResponse;
import org.monitoring.catchholebackend.domain.upload.service.SettingBookService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/setting-books")
@Tag(name = "SettingBook", description = "작품별 설정집 원본 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class SettingBookController {

    private final SettingBookService settingBookService;

    @GetMapping
    @Operation(
            operationId = "getSettingBooks",
            summary = "활성 설정집 원본 목록 조회",
            description = "본인 작품의 삭제되지 않은 설정집 원본을 최근 업로드 순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<SettingBookSummaryResponse>> getSettingBooks(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(settingBookService.getSettingBooks(member.memberId(), workId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "uploadSettingBook",
            summary = "설정집 원본 단독 업로드",
            description = "TXT, DOCX, HWP 또는 HWPX 원문과 기준 시점을 불변 버전으로 저장하고 자동 분석을 접수합니다. 기준 시점은 미지정이 기본이며 특정 회차는 등록 여부와 관계없이 해당 회차 종료 시점을 뜻합니다. 사용량 사전 확인 실패 시 업로드하지 않습니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 업로드 성공"),
            @ApiResponse(responseCode = "400", description = "파일 형식, 크기 또는 내용 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<SettingBookSummaryResponse> uploadSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "UNSPECIFIED") SettingBookReferenceTimeKind referenceTimeKind,
            @RequestParam(required = false) Integer referenceEpisodeNo
    ) {
        return CommonResponse.success(
                "설정집 원본이 업로드되었습니다.",
                settingBookService.uploadSettingBook(member.memberId(), workId, file, referenceTimeKind, referenceEpisodeNo)
        );
    }

    @GetMapping("/{settingBookId}")
    @Operation(
            operationId = "getSettingBook",
            summary = "설정집 전체 원문 조회",
            description = "선택한 설정집의 현재 편집용 텍스트와 업로드 원본 메타데이터를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 원문 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음")
    })
    public CommonResponse<SettingBookResponse> getSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        return CommonResponse.success(
                settingBookService.getSettingBook(member.memberId(), workId, settingBookId)
        );
    }

    @GetMapping("/{settingBookId}/revisions/{revisionId}")
    @Operation(operationId = "getSettingBookRevision", summary = "분석 당시 설정집 원문 조회",
            description = "본인 작품의 설정집과 불변 원문 버전 소속을 검증합니다. 설정집 수정/보관 후에도 분석 당시 원문을 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "불변 원문 조회 성공"),
            @ApiResponse(responseCode = "404", description = "작품, 설정집 또는 해당 원문 버전을 찾을 수 없음")})
    public CommonResponse<org.monitoring.catchholebackend.domain.upload.dto.response.SettingBookRevisionResponse> getSettingBookRevision(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID settingBookId, @PathVariable UUID revisionId) {
        return CommonResponse.success(settingBookService.getSettingBookRevision(member.memberId(), workId, settingBookId, revisionId));
    }

    @PatchMapping("/{settingBookId}")
    @Operation(
            operationId = "updateSettingBook",
            summary = "설정집 전체 원문 수정",
            description = "편집 시작 시 읽은 expectedRevisionNo(구형 설정집의 revisionNo=null은 0)를 먼저 검사합니다. 오래된 번호는 동일 내용 저장도 409로 거절하며 원문·시점·분석·저장 객체를 변경하지 않습니다. 원문 또는 기준 시점 변경을 새 불변 버전으로 저장하고 이전 미완료 분석을 무효화합니다. 기준 시점 필드 생략은 기존 시점을 유지하며 원문과 시점이 모두 같으면 새 버전을 만들지 않습니다. 저장만으로 분석을 시작하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 원문 수정 성공"),
            @ApiResponse(responseCode = "400", description = "편집 기준 번호 누락·음수 또는 원문 내용·크기 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "UPLOAD_SETTING_BOOK_REVISION_CONFLICT: 다른 저장으로 편집 기준 버전이 변경됨")
    })
    public CommonResponse<SettingBookResponse> updateSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId,
            @Valid @RequestBody SettingBookUpdateRequest request
    ) {
        return CommonResponse.success(
                "설정집 원문이 수정되었습니다.",
                settingBookService.updateSettingBook(member.memberId(), workId, settingBookId, request)
        );
    }

    @PostMapping("/{settingBookId}/analysis-jobs")
    @Operation(operationId = "analyzeSettingBook", summary = "설정집 분석·재분석 접수",
            description = "최신 저장 원문을 자동 분석합니다. 같은 원문의 진행 중 작업을 반환하고 실패 작업은 checkpoint부터 재개합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "자동 분석 접수 성공"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "분석 입력 또는 상태 충돌"),
            @ApiResponse(responseCode = "429", description = "분석 사용량 부족")})
    public CommonResponse<org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse> analyzeSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID settingBookId) {
        return CommonResponse.success("설정집 분석을 접수했습니다.", settingBookService.analyzeSettingBook(member.memberId(), workId, settingBookId));
    }

    @DeleteMapping("/{settingBookId}")
    @Operation(
            operationId = "deleteSettingBook",
            summary = "설정집 원본 soft delete",
            description = "설정집을 활성 목록에서 숨기고 미완료 분석을 무효화합니다. 확정된 설정과 분석 근거는 보존합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정집 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 설정집을 찾을 수 없음")
    })
    public CommonResponse<Void> deleteSettingBook(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID settingBookId
    ) {
        settingBookService.deleteSettingBook(member.memberId(), workId, settingBookId);
        return CommonResponse.success("설정집 원본이 삭제되었습니다.", null);
    }
}
