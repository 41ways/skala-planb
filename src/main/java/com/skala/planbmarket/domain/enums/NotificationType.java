package com.skala.planbmarket.domain.enums;

/**
 * 알림 종류.
 *
 * 이 도메인은 사용자가 가만히 있으면 손해를 보는 구조라서 알림이 부가기능이 아님.
 * 결제 시한을 놓치면 예약금이 몰수되고, 티켓은 알아서 소멸함.
 */
public enum NotificationType {

    /** 결제 마감 임박 */
    PAYMENT_DEADLINE,

    /** 예약이 제한시간을 넘겨 취소됨 */
    RESERVATION_EXPIRED,

    /** 예약금이 몰수됨 */
    DEPOSIT_FORFEITED,

    /** 보유 티켓 만료 임박 */
    EXPIRY_WARNING,

    /** 티켓이 실효됨 */
    TICKET_EXPIRED,

    /** 거래 확정됨 */
    ESCROW_CONFIRMED,

    /** 판매자가 철회함 */
    LISTING_WITHDRAWN
}
