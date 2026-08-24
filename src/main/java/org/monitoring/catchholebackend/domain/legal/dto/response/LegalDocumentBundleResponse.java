package org.monitoring.catchholebackend.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입과 공개 화면에서 함께 사용하는 현재 법률 문서 묶음")
public record LegalDocumentBundleResponse(
        @Schema(description = "문서 언어·지역", example = "ko-KR")
        String locale,

        LegalDocumentResponse termsOfService,

        LegalDocumentResponse privacyPolicy
) {
}
