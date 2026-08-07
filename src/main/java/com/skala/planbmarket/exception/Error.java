package com.skala.planbmarket.exception;

import lombok.Getter;

/**
 * 업무 예외 목록. 상태 코드와 메시지를 한 곳에 묶어둠.
 *
 * 컨트롤러마다 상태 코드를 손으로 정하면 같은 상황에 400이 나왔다 409가 나왔다 함.
 * 여기 한 줄로 정해두면 그럴 일이 없음.
 *
 * 400과 409를 가르는 기준: 요청 자체가 잘못됐으면 400, 요청은 멀쩡한데 지금 서버 상태가
 * 받아줄 수 없으면 409. 예를 들어 본인 티켓 구매는 애초에 말이 안 되는 요청이라 400이고,
 * 이미 팔린 티켓 구매는 요청은 정상인데 한발 늦은 거라 409임.
 */
@Getter
public enum Error {

    DATA_NOT_FOUND(404, "데이터를 찾을 수 없습니다"),
    DATA_DUPLICATED(409, "이미 존재하는 데이터입니다"),
    NOT_AUTHENTICATED(401, "로그인이 필요합니다"),
    LOGIN_FAILED(401, "아이디 또는 비밀번호가 올바르지 않습니다"),
    NO_PERMISSION(403, "권한이 없습니다"),
    RESERVED_ACCOUNT_ID(400, "시스템이 예약한 ID라 사용할 수 없습니다"),

    INSUFFICIENT_BALANCE(400, "예치금이 부족합니다"),
    TICKET_EXPIRED(400, "이미 만료된 티켓입니다"),
    TICKET_NOT_EDITABLE(409, "판매 등록된 티켓은 수정·삭제할 수 없습니다"),
    TICKET_ALREADY_LISTED(409, "이미 판매 등록된 티켓입니다"),
    LISTING_NOT_OPEN(409, "구매 가능한 상태가 아닙니다"),
    ALREADY_SOLD(409, "이미 판매된 티켓입니다"),
    ALREADY_RESERVED(409, "다른 구매자가 예약 중입니다"),
    RESERVATION_NOT_FOUND(404, "진행 중인 예약이 없습니다"),
    PAYMENT_DEADLINE_PASSED(400, "결제 제한시간이 지났습니다"),
    SELF_TRADE_NOT_ALLOWED(400, "본인 티켓은 구매할 수 없습니다"),
    ESCROW_EXISTS(409, "진행 중인 거래가 있어 철회할 수 없습니다"),
    EXTEND_NOT_SUPPORTED(400, "연장할 수 없는 카테고리입니다"),
    MEMBER_HAS_HISTORY(409, "거래 이력이 남아 있어 삭제할 수 없습니다");

    private final int status;
    private final String message;

    Error(int status, String message) {
        this.status = status;
        this.message = message;
    }
}
