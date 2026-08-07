package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;

import com.skala.planbmarket.common.TradePolicy;
import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.enums.EscrowStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 거래(에스크로) 응답.
 *
 * 결제액과 판매자 실수령액을 같이 내려줌. 수수료가 붙어서 둘이 다른데,
 * 한 화면에서 같이 봐야 돈이 어디로 갔는지가 이해됨.
 */
@Schema(name = "거래 응답")
public record EscrowResponse(
        Long id,
        Long listingId,
        String buyerId,
        String sellerId,
        Integer quantity,

        @Schema(description = "결제 총액 (예약금 충당분 포함)")
        Long amount,

        @Schema(description = "정산 기준 금액. 여기서 수수료를 뗌")
        Long sellerAmount,

        @Schema(description = "중개 수수료")
        Long commission,

        @Schema(description = "판매자가 실제로 받는 금액")
        Long sellerPayout,

        EscrowStatus status,
        LocalDateTime paidAt,

        @Schema(description = "이 시각이 지나면 자동으로 확정됨")
        LocalDateTime autoConfirmAt,

        LocalDateTime confirmedAt,

        @Schema(description = "티켓 만료 시각")
        LocalDateTime ticketExpiresAt,

        String ticketTitle
) {

    public static EscrowResponse from(Escrow escrow) {
        long sellerAmount = escrow.sellerAmount();
        long commission = TradePolicy.commissionOf(sellerAmount);

        return new EscrowResponse(
                escrow.getId(),
                escrow.getListing().getId(),
                escrow.getBuyer().getId(),
                escrow.getListing().getSeller().getId(),
                escrow.getQuantity(),
                escrow.getAmount(),
                sellerAmount,
                commission,
                sellerAmount - commission,
                escrow.getStatus(),
                escrow.getPaidAt(),
                escrow.getAutoConfirmAt(),
                escrow.getConfirmedAt(),
                escrow.getListing().getTicket().getExpiresAt(),
                escrow.getListing().getTicket().getTitle());
    }
}
