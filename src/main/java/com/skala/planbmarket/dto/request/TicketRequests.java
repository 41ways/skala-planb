package com.skala.planbmarket.dto.request;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.skala.planbmarket.domain.enums.Category;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 티켓 관련 요청 DTO 모음.
 *
 * 날짜 필드들이 전부 nullable인 게 어색해 보이는데 이유가 있음. 카테고리마다 채워야 할
 * 날짜가 다름 — 영화는 eventAt, 전시는 validFrom/validUntil. 그래서 애노테이션만으로는
 * "이 카테고리엔 이 필드가 필수"를 표현할 수 없고, 카테고리를 본 뒤 서비스에서 따져야 함.
 * 그 검증은 TicketService.validateDates()에 있음.
 */
public final class TicketRequests {

    private TicketRequests() {
    }

    @Schema(name = "티켓 등록 요청")
    public record Create(
            @NotNull(message = "카테고리는 필수입니다")
            Category category,

            @NotBlank(message = "제목은 필수입니다")
            @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
            String title,

            @NotNull(message = "정가는 필수입니다")
            @Positive(message = "정가는 0보다 커야 합니다")
            Long originalPrice,

            @NotNull(message = "수량은 필수입니다")
            @Min(value = 1, message = "수량은 1 또는 2여야 합니다")
            @Max(value = 2, message = "수량은 1 또는 2여야 합니다")
            Integer quantity,

            @Schema(description = "좌석·구역 표기. 선택 (항공 '27A, 27B' / 기차 '4호차 12석' / 영화 '2관 H열')")
            @Size(max = 100, message = "좌석 표기는 100자를 넘을 수 없습니다")
            String seatInfo,

            @Schema(description = "양도 시 함께 넘어가는 적립 마일. 항공권용, 선택")
            @PositiveOrZero(message = "마일은 0 이상이어야 합니다")
            Long mileage,

            @Schema(description = "시점 만료 카테고리(영화·콘서트·스포츠·기차·항공)에서 필수")
            @Future(message = "이미 지난 시각으로는 등록할 수 없습니다")
            LocalDateTime eventAt,

            @Schema(description = "기간 만료 카테고리(전시·호텔·기프티콘)에서 필수")
            LocalDate validFrom,

            @Schema(description = "기간 만료 카테고리에서 필수")
            LocalDate validUntil
    ) {
    }

    @Schema(name = "티켓 수정 요청", description = "판매 등록 전(OWNED) 상태에서만 가능")
    public record Update(
            @NotBlank(message = "제목은 필수입니다")
            @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
            String title,

            @NotNull(message = "정가는 필수입니다")
            @Positive(message = "정가는 0보다 커야 합니다")
            Long originalPrice,

            @NotNull(message = "수량은 필수입니다")
            @Min(value = 1, message = "수량은 1 또는 2여야 합니다")
            @Max(value = 2, message = "수량은 1 또는 2여야 합니다")
            Integer quantity,

            @Schema(description = "좌석·구역 표기. 선택 (항공 '27A, 27B' / 기차 '4호차 12석' / 영화 '2관 H열')")
            @Size(max = 100, message = "좌석 표기는 100자를 넘을 수 없습니다")
            String seatInfo,

            @Schema(description = "양도 시 함께 넘어가는 적립 마일. 항공권용, 선택")
            @PositiveOrZero(message = "마일은 0 이상이어야 합니다")
            Long mileage,

            @Future(message = "이미 지난 시각으로는 수정할 수 없습니다")
            LocalDateTime eventAt,

            LocalDate validFrom,

            LocalDate validUntil
    ) {
    }

    @Schema(name = "기한 연장 요청", description = "기프티콘(EXTENDABLE) 전용")
    public record Extend(
            @NotNull(message = "연장할 기한은 필수입니다")
            @Future(message = "연장 기한은 미래여야 합니다")
            LocalDate extendedUntil
    ) {
    }
}
