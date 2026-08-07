package com.skala.planbmarket.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 관리·검증 요청 DTO 모음. */
public final class AdminRequests {

    private AdminRequests() {
    }

    @Schema(name = "동시성 테스트 요청")
    public record SimulateConcurrent(

            @Schema(description = "경합시킬 판매 건. OPEN 상태여야 함", example = "4")
            @NotNull(message = "판매 ID는 필수입니다")
            Long listingId,

            @Schema(description = "동시에 쏠 스레드 수", example = "20")
            @NotNull(message = "스레드 수는 필수입니다")
            @Min(value = 2, message = "2 이상이어야 경합이 생깁니다")
            @Max(value = 50, message = "50을 넘기지 않습니다")
            Integer threadCount,

            @Schema(description = """
                    false면 락 없이 — 판매 건 하나에 예약이 여러 건 생기는 걸 볼 수 있음.
                    true면 비관적 락 — 1건만 성공하고 나머지는 ALREADY_RESERVED.
                    두 응답을 나란히 놓는 게 이 프로젝트의 핵심 증빙""",
                    example = "false")
            @NotNull(message = "락 사용 여부는 필수입니다")
            Boolean useLock
    ) {
    }
}
