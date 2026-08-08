package com.skala.planbmarket.domain.enums;

/**
 * 티켓 상태.
 *
 * 전량 양도만 있으므로 거래가 확정되면 소유자가 바뀌고 바로 TRANSFERRED가 됨.
 * (SPEC에는 2매를 두 명이 나눠 사는 흐름이 있었지만 5단계에서 걷어냈음 —
 *  그때 분할 발행 개념도 같이 사라졌다)
 *
 * OWNED → LISTED → TRANSFERRED / EXPIRED. 철회하면 LISTED에서 OWNED로 되돌아감.
 */
public enum TicketStatus {

    /** 보유 중, 아직 판매 등록 안 함 */
    OWNED,

    /** 판매 등록됨 */
    LISTED,

    /** 전량 양도 완료 */
    TRANSFERRED,

    /** 만료로 실효됨 */
    EXPIRED
}
