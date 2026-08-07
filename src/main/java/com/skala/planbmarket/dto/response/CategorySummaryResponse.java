package com.skala.planbmarket.dto.response;

import java.util.List;

import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.ExpiryType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 카테고리별 거래 현황.
 *
 * 실효율의 분모를 <b>"결말이 난 티켓"(양도 완료 + 실효)</b>으로 잡음.
 * 전체 티켓 수로 나누면 아직 판매 중인 매물이 분모에 들어가서, 새 티켓이 등록될 때마다
 * 실효율이 저절로 내려감 — 아무 일도 안 일어났는데 지표가 좋아지는 건 지표가 아님.
 */
@Schema(name = "카테고리별 거래 현황 응답")
public record CategorySummaryResponse(

        List<Row> categories,

        @Schema(description = "전 카테고리 합계")
        Totals totals
) {

    @Schema(name = "카테고리 현황 한 줄")
    public record Row(

            Category category,
            String displayName,

            @Schema(description = "만료 처리 방식")
            ExpiryType expiryType,

            @Schema(description = "해당 카테고리 티켓 총 건수")
            long ticketCount,

            @Schema(description = "양도 완료 건수")
            long tradedCount,

            @Schema(description = "실효 건수")
            long expiredCount,

            @Schema(description = "실효율 = 실효 / (양도완료 + 실효). 결말이 난 게 없으면 0")
            double expiryRate,

            @Schema(description = "확정된 거래 금액 합")
            long tradedAmount,

            @Schema(description = "실효된 티켓의 정가 합")
            long lostAmount,

            @Schema(description = "평균 거래가율 (거래가 / 정가). 거래가 없으면 0")
            double avgRatio
    ) {
    }

    @Schema(name = "카테고리 현황 합계")
    public record Totals(
            long ticketCount,
            long tradedCount,
            long expiredCount,
            double expiryRate,
            long tradedAmount,
            long lostAmount
    ) {
    }
}
