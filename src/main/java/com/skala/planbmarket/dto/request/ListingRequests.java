package com.skala.planbmarket.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 판매 등록 요청 DTO. */
public final class ListingRequests {

    private ListingRequests() {
    }

    @Schema(name = "판매 등록 요청")
    public record Create(
            @NotNull(message = "티켓 ID는 필수입니다")
            Long ticketId,

            @Schema(description = "전체 희망가. 2매 티켓이면 2매 합친 금액")
            @NotNull(message = "희망가는 필수입니다")
            @Positive(message = "희망가는 0보다 커야 합니다")
            Long askingPrice
    ) {
    }
}
