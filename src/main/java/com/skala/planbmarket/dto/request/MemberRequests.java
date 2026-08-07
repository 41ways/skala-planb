package com.skala.planbmarket.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 회원 관련 요청 DTO 모음.
 *
 * 셋 다 필드가 두 개뿐이라 파일을 따로 쪼개면 오히려 찾아다니기 번거로움.
 * 대신 서로 다른 검증 규칙이 붙는다는 걸 보여주려고 타입은 나눠둠 —
 * 가입은 ID 형식까지 보지만 로그인은 형식을 안 봄. 로그인에서 형식을 따지면
 * "그런 ID는 없다"는 정보를 형식 오류로 흘리는 셈이라 굳이 볼 이유가 없음.
 */
public final class MemberRequests {

    private MemberRequests() {
    }

    @Schema(name = "회원가입 요청")
    public record Create(
            @NotBlank(message = "회원 ID는 필수입니다")
            @Size(min = 4, max = 20, message = "회원 ID는 4~20자여야 합니다")
            @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                    message = "회원 ID는 영문 소문자로 시작하고 소문자·숫자·밑줄만 쓸 수 있습니다")
            String id,

            @NotBlank(message = "비밀번호는 필수입니다")
            @Size(min = 8, max = 30, message = "비밀번호는 8~30자여야 합니다")
            String password
    ) {
    }

    @Schema(name = "로그인 요청")
    public record Login(
            @NotBlank(message = "회원 ID는 필수입니다")
            String id,

            @NotBlank(message = "비밀번호는 필수입니다")
            String password
    ) {
    }

    @Schema(name = "예치금 충전 요청")
    public record Charge(
            @Schema(description = "충전 금액. 실제 결제는 없고 EXTERNAL 계정에서 들어오는 것으로 기록됨")
            @NotNull(message = "충전 금액은 필수입니다")
            @Positive(message = "충전 금액은 0보다 커야 합니다")
            @Max(value = 10_000_000, message = "한 번에 1,000만원까지 충전할 수 있습니다")
            Long amount
    ) {
    }

    @Schema(name = "회원 정보 수정 요청")
    public record Update(
            @NotBlank(message = "회원 ID는 필수입니다")
            String id,

            @NotBlank(message = "비밀번호는 필수입니다")
            @Size(min = 8, max = 30, message = "비밀번호는 8~30자여야 합니다")
            String password
    ) {
    }
}
