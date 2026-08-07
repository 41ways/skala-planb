package com.skala.planbmarket.domain.enums;

/**
 * 원장 기록 사유.
 *
 * 사유 하나가 곧 원장 2줄(DEBIT 1 + CREDIT 1)이 됨. 어느 계정에서 어느 계정으로 가는지는
 * 아래 표대로 고정임. 이 표를 벗어나는 기록은 만들지 말 것.
 *
 *   CHARGE           EXTERNAL     → 회원
 *   DEPOSIT_HOLD     회원         → DEPOSIT_POOL
 *   DEPOSIT_RELEASE  DEPOSIT_POOL → 회원
 *   DEPOSIT_FORFEIT  DEPOSIT_POOL → PLATFORM
 *   DEPOSIT_CAPTURE  DEPOSIT_POOL → ESCROW_POOL
 *   PURCHASE         회원         → ESCROW_POOL
 *   ESCROW_REFUND    ESCROW_POOL  → 회원
 *   COMMISSION       ESCROW_POOL  → PLATFORM
 *   SELLER_SETTLE    ESCROW_POOL  → 판매자
 */
public enum LedgerReason {

    /** 예치금 충전 */
    CHARGE,

    /** 예약금 홀드 */
    DEPOSIT_HOLD,

    /** 예약금을 본결제에 충당 */
    DEPOSIT_CAPTURE,

    /** 예약금 몰수 */
    DEPOSIT_FORFEIT,

    /** 예약금 환불 */
    DEPOSIT_RELEASE,

    /** 구매 결제 */
    PURCHASE,

    /** 에스크로 환불 */
    ESCROW_REFUND,

    /**
     * 중개 수수료. 정산할 때 판매자 몫에서 먼저 떼어 플랫폼으로 감.
     *
     * 이게 있어야 플랫폼 수익이 "예약금 몰수"에만 의존하지 않게 됨.
     * 몰수만이 수입원이면 플랫폼이 이탈을 반길 유인이 생겨서 구조가 뒤틀림.
     */
    COMMISSION,

    /** 판매자 정산 (수수료 뗀 나머지) */
    SELLER_SETTLE
}
