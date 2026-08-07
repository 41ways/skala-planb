package com.skala.planbmarket.domain.enums;

/**
 * 티켓 상태.
 *
 * 2매짜리를 두 명이 1매씩 나눠 산 경우엔 거래 확정마다 1매짜리 티켓을 새로 발행하고
 * 원본 quantity를 깎음. 원본이 0이 되는 순간 TRANSFERRED로 넘어감.
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
