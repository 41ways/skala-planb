package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;

import com.skala.planbmarket.common.TradePolicy;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.enums.ListingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 판매 등록 응답.
 *
 * 티켓 정보를 통째로 품고 나가는 이유는, 목록 화면에서 잔여시간과 카테고리를 같이
 * 보여줘야 하는데 티켓을 따로 조회하게 하면 목록 한 페이지에 20번 더 부르게 됨.
 */
@Schema(name = "판매 등록 응답")
public record ListingResponse(
        Long id,
        String sellerId,
        Long askingPrice,

        @Schema(description = "예약할 때 홀드될 예약금")
        Long depositAmount,

        ListingStatus status,
        LocalDateTime createdAt,
        TicketResponse ticket
) {

    public static ListingResponse from(Listing listing) {
        return from(listing, LocalDateTime.now());
    }

    public static ListingResponse from(Listing listing, LocalDateTime now) {
        return new ListingResponse(
                listing.getId(),
                listing.getSeller().getId(),
                listing.getAskingPrice(),
                TradePolicy.depositOf(listing.getAskingPrice()),
                listing.getStatus(),
                listing.getCreatedAt(),
                TicketResponse.from(listing.getTicket(), now));
    }
}
