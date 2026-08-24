package org.monitoring.catchholebackend.domain.legal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentBundleResponse;
import org.monitoring.catchholebackend.domain.legal.dto.response.LegalDocumentResponse;
import org.monitoring.catchholebackend.domain.legal.service.LegalDocumentService;
import org.monitoring.catchholebackend.domain.legal.service.LegalDocumentServiceImpl;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/legal-documents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Legal Document", description = "공개 이용약관·개인정보처리방침 원문 API")
public class LegalDocumentController {

    private final LegalDocumentService legalDocumentService;

    @GetMapping("/current")
    @Operation(
            operationId = "getCurrentLegalDocuments",
            summary = "현재 법률 문서 조회",
            description = "회원가입과 공개 화면에서 사용할 현재 PUBLISHED 이용약관과 개인정보처리방침을 함께 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 문서 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "locale 형식 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "현재 공개 문서 구성 누락",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<LegalDocumentBundleResponse> getCurrentLegalDocuments(
            @RequestParam(defaultValue = LegalDocumentServiceImpl.DEFAULT_LOCALE)
            @Size(max = 10, message = "locale은 10자 이하여야 합니다.")
            @Pattern(regexp = "[a-z]{2}-[A-Z]{2}", message = "locale 형식이 올바르지 않습니다.")
            String locale
    ) {
        return CommonResponse.success(legalDocumentService.getCurrentDocuments(locale));
    }

    @GetMapping("/{documentId}")
    @Operation(
            operationId = "getLegalDocument",
            summary = "공개 법률 문서 원문 조회",
            description = "PUBLISHED 또는 RETIRED 문서를 식별자로 조회합니다. DRAFT 문서는 공개하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "문서 조회 성공"),
            @ApiResponse(
                    responseCode = "404",
                    description = "공개 가능한 문서 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<LegalDocumentResponse> getLegalDocument(
            @PathVariable @Positive(message = "문서 식별자는 양수여야 합니다.") Long documentId
    ) {
        return CommonResponse.success(legalDocumentService.getPublicDocument(documentId));
    }
}
