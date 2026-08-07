package com.skala.planbmarket.domain.enums;

/**
 * 에스크로(거래) 상태.
 *
 * REFUNDED와 VOIDED는 돈 흐름은 똑같이 구매자에게 되돌아가지만 원인이 달라서 나눠놨음.
 * 구매자 의사로 물린 건지, 시간이 다 돼서 거래 자체가 무의미해진 건지 구분이 남아야
 * 나중에 실효 손실 통계를 낼 수 있음.
 */
public enum EscrowStatus {

    /** 결제됨. 돈은 ESCROW_POOL에 보관 중 */
    HOLDING,

    /** 확정됨. 판매자에게 정산 완료 */
    CONFIRMED,

    /** 환불됨 (구매자 요청 또는 부분성사 제안 거절) */
    REFUNDED,

    /** 티켓이 만료돼서 무산됨. 전액 환불 */
    VOIDED
}
