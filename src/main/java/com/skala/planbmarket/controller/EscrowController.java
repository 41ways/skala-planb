package com.skala.planbmarket.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.common.TradePolicy;
import com.skala.planbmarket.dto.response.EscrowResponse;
import com.skala.planbmarket.dto.response.ReservationResponse;
import com.skala.planbmarket.service.EscrowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 예약 → 결제 → 확정.
 *
 * 예약·결제는 판매 건 기준이라 경로가 /api/listings/{id}/... 이고,
 * 결제가 끝난 뒤 확정·환불은 거래 기준이라 /api/escrows/{id}/... 로 감.
 */
@Tag(name = "4. 거래", description = "예약금 → 본결제 → 에스크로 확정")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EscrowController {

    private final EscrowService escrowService;

    @Operation(summary = "구매 예약",
            description = "희망가의 " + TradePolicy.DEPOSIT_PERCENT + "%가 예약금으로 홀드되고 "
                    + "판매 건이 RESERVED로 잠김. 결제 제한시간은 min(30분, 남은시간 × 0.5)")
    @PostMapping("/listings/{id}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ReservationResponse> reserve(@PathVariable Long id) {
        return Response.created(escrowService.reserve(id));
    }

    @Operation(summary = "예약 현황", description = "예약 당사자와 판매자만 조회 가능")
    @GetMapping("/listings/{id}/reserve")
    public Response<ReservationResponse> reservation(@PathVariable Long id) {
        return Response.success(escrowService.reservationOf(id));
    }

    @Operation(summary = "예약 취소",
            description = "예약 후 " + TradePolicy.COOLING_OFF_MINUTES + "분 이내면 예약금 전액 환불, "
                    + "지나면 몰수됨")
    @DeleteMapping("/listings/{id}/reserve")
    public Response<ReservationResponse> cancelReservation(@PathVariable Long id) {
        return Response.success(escrowService.cancelReservation(id));
    }

    @Operation(summary = "본결제",
            description = "예약금이 결제액에 충당되고 나머지만 잔액에서 빠짐. "
                    + "결제액은 ESCROW_POOL에 보관되고 확정될 때 판매자에게 감")
    @PostMapping("/listings/{id}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EscrowResponse> pay(@PathVariable Long id) {
        return Response.created(escrowService.pay(id));
    }

    @Operation(summary = "거래 상세", description = "거래 당사자(구매자·판매자)만 조회 가능")
    @GetMapping("/escrows/{id}")
    public Response<EscrowResponse> get(@PathVariable Long id) {
        return Response.success(escrowService.get(id));
    }

    @Operation(summary = "구매 확정",
            description = "판매자에게 정산됨. 수수료를 뗀 금액이 판매자에게 감")
    @PostMapping("/escrows/{id}/confirm")
    public Response<EscrowResponse> confirm(@PathVariable Long id) {
        return Response.success(escrowService.confirm(id));
    }

    @Operation(summary = "환불 요청",
            description = "결제 후 " + TradePolicy.COOLING_OFF_MINUTES + "분 이내만 가능")
    @PostMapping("/escrows/{id}/refund")
    public Response<EscrowResponse> refund(@PathVariable Long id) {
        return Response.success(escrowService.refund(id));
    }
}
