package org.monitoring.catchholebackend.global.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
class GlobalExceptionHandlerTestController {

    @GetMapping("/success")
    CommonResponse<TestResponse> success() {
        return CommonResponse.success(new TestResponse("catchhole"));
    }

    @PostMapping("/validation")
    CommonResponse<TestResponse> validation(@Valid @RequestBody TestRequest request) {
        return CommonResponse.success(new TestResponse(request.email()));
    }

    @GetMapping("/not-found")
    CommonResponse<Void> notFound() {
        throw new AppException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping("/conflict")
    CommonResponse<Void> conflict() {
        throw new AppException(CommonErrorCode.CONFLICT, "이미 사용 중인 이메일입니다.");
    }

    @GetMapping("/unknown-error")
    CommonResponse<Void> unknownError() {
        throw new IllegalStateException("database password leaked");
    }

    record TestRequest(
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
    }

    record TestResponse(String name) {
    }
}
