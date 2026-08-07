package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 정합성 자가검증 결과.
 *
 * 세 가지를 서로 다른 각도에서 봄:
 *   1) 회원마다 잔액과 원장이 맞는가        → 개별 계정이 어긋나지 않았나
 *   2) 전체 차변과 대변이 같은가            → 돈이 시스템 밖으로 새거나 생겨나지 않았나
 *   3) 보관 계정 잔액과 진행 중 거래액이 같은가 → 묶어둔 돈이 실제로 그만큼 있나
 *
 * 1번만 보면 "모두가 똑같이 틀린" 경우를 못 잡고, 2번만 보면 "누구 것인지 뒤바뀐"
 * 경우를 못 잡음. 3번은 에스크로가 원장과 따로 노는 걸 잡아줌.
 * 각도가 달라야 서로의 사각지대를 덮음.
 */
@Schema(name = "정합성 검증 응답")
public record IntegrityCheckResponse(

        @Schema(description = "세 검사를 모두 통과했는지")
        boolean passed,

        @Schema(description = "규칙 1 — 모든 회원의 잔액이 원장 합과 일치")
        boolean memberBalanceMatch,

        @Schema(description = "어긋난 회원 목록. 비어 있어야 정상")
        List<MismatchedMember> mismatchedMembers,

        @Schema(description = "규칙 2 — 전체 차대 일치")
        boolean ledgerBalanced,

        long totalDebit,
        long totalCredit,

        @Schema(description = "규칙 3 — ESCROW_POOL 잔액이 보관 중인 거래 금액 합과 일치")
        boolean escrowPoolMatch,

        @Schema(description = "ESCROW_POOL 계정의 현재 잔액")
        long escrowPoolBalance,

        @Schema(description = "HOLDING 상태 거래들의 금액 합")
        long heldEscrowTotal,

        @Schema(description = "규칙 3 — DEPOSIT_POOL 잔액이 홀드 중인 예약금 합과 일치")
        boolean depositPoolMatch,

        @Schema(description = "DEPOSIT_POOL 계정의 현재 잔액")
        long depositPoolBalance,

        @Schema(description = "HELD 상태 예약금의 합")
        long heldDepositTotal,

        @Schema(description = "결제 원장이 남지 않은 거래 건수")
        long orphanEscrows,

        @Schema(description = "홀드 원장이 남지 않은 예약금 건수")
        long orphanDeposits,

        @Schema(description = "플랫폼 누적 수익 (수수료 + 몰수분)")
        long platformBalance,

        LocalDateTime checkedAt
) {

    @Schema(name = "잔액 불일치 회원")
    public record MismatchedMember(
            String memberId,
            @Schema(description = "member 테이블에 적힌 잔액") long recordedBalance,
            @Schema(description = "원장을 합산해서 나온 잔액") long ledgerBalance,
            long difference
    ) {
    }
}
