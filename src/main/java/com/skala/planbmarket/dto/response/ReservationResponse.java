package com.skala.planbmarket.dto.response;

import java.time.Duration;
import java.time.LocalDateTime;

import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.ListingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 예약 응답.
 *
 * remainingAmount를 따로 내려주는 이유: 예약금이 본결제에 충당되니까 실제로 더 낼 돈은
 * 희망가에서 예약금을 뺀 금액임. 그걸 클라이언트가 다시 계산하게 하면 어디선가 틀림.
 *
 * remainingMinutes도 서버가 계산해서 줌. 제한시간이 지났는지는 서버 시계가 기준이어야
 * 하는데, 클라이언트가 자기 시계로 재면 기기 시각이 틀어졌을 때 엉뚱한 화면이 나옴.
 */
@Schema(name = "예약 응답")
public record ReservationResponse(
        Long depositId,
        Long listingId,
        String buyerId,
        String sellerId,

        @Schema(description = "판매 희망가 (결제 총액)")
        Long askingPrice,

        @Schema(description = "홀드된 예약금")
        Long depositAmount,

        @Schema(description = "결제할 때 추가로 낼 금액 (희망가 - 예약금)")
        Long remainingAmount,

        DepositStatus depositStatus,
        ListingStatus listingStatus,

        LocalDateTime heldAt,

        @Schema(description = "청약철회(전액 환불) 가능 마감 시각. 이후 취소하면 몰수")
        LocalDateTime coolingOffUntil,

        @Schema(description = "이 시각까지 결제해야 함. 넘기면 예약금 몰수")
        LocalDateTime paymentDeadline,

        @Schema(description = "결제 마감까지 남은 분. 이미 지났으면 음수")
        long remainingMinutes,

        LocalDateTime resolvedAt,
        String ticketTitle
) {

    public static ReservationResponse from(Deposit deposit, long coolingOffMinutes) {
        LocalDateTime now = LocalDateTime.now();
        long askingPrice = deposit.getListing().getAskingPrice();

        return new ReservationResponse(
                deposit.getId(),
                deposit.getListing().getId(),
                deposit.getMember().getId(),
                deposit.getListing().getSeller().getId(),
                askingPrice,
                deposit.getAmount(),
                askingPrice - deposit.getAmount(),
                deposit.getStatus(),
                deposit.getListing().getStatus(),
                deposit.getHeldAt(),
                deposit.getHeldAt().plusMinutes(coolingOffMinutes),
                deposit.getPaymentDeadline(),
                Duration.between(now, deposit.getPaymentDeadline()).toMinutes(),
                deposit.getResolvedAt(),
                deposit.getListing().getTicket().getTitle());
    }
}
