package com.turkey.quick.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CustomerSignupRequest(

        @Schema(description = "로그인 ID", example = "quick_user01")
        @NotBlank(message = "로그인 ID는 필수입니다.")
        @Size(max = 50, message = "로그인 ID는 50자를 넘을 수 없습니다.")
        String loginId,

        @Schema(description = "비밀번호(8자 이상)", example = "p@ssw0rd")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        String password,

        @Schema(description = "비밀번호 확인", example = "p@ssw0rd")
        @NotBlank(message = "비밀번호 확인은 필수입니다.")
        String passwordConfirm,

        @Schema(description = "이름", example = "홍길동")
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다.")
        String name,

        @Schema(description = "휴대전화 번호(하이픈 선택)", example = "010-1234-5678")
        @NotBlank(message = "휴대전화 번호는 필수입니다.")
        @Pattern(regexp = "^01(?:0|1|[6-9])-?\\d{3,4}-?\\d{4}$", message = "휴대전화 번호 형식이 올바르지 않습니다.")
        String phoneNumber,

        @Schema(description = "휴대전화 인증 완료 토큰(/api/phone-verifications/confirm 응답)", example = "3f7c...")
        @NotBlank(message = "휴대전화 인증이 필요합니다.")
        String phoneVerificationToken,

        @Schema(description = "동의한 약관 ID 목록(필수 약관을 모두 포함해야 한다)", example = "[1, 2]")
        @NotNull(message = "약관 동의 정보는 필수입니다.")
        List<@NotNull(message = "약관 ID에는 null을 포함할 수 없습니다.") Long> agreedTermIds
) {
}
