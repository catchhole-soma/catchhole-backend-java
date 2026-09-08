package org.monitoring.catchholebackend.domain.auth.email;

import java.util.Locale;

/** 기존 이메일 로그인·unique 정책을 유지하며 입력 양끝 공백만 제거한다. */
public final class EmailAddressNormalizer {

    private EmailAddressNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim();
    }

    /** 발송 한도와 활성 인증 흐름에서만 동일 도메인의 대소문자 변형을 하나로 취급한다. */
    public static String normalizeForVerificationKey(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return null;
        }
        int domainStart = normalized.lastIndexOf('@') + 1;
        if (domainStart == 0) {
            return normalized;
        }
        return normalized.substring(0, domainStart)
                + normalized.substring(domainStart).toLowerCase(Locale.ROOT);
    }
}
