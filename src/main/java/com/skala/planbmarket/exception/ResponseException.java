package com.skala.planbmarket.exception;

import lombok.Getter;

/**
 * 업무 예외. "요청은 받았는데 규칙상 못 해준다"는 상황에 던짐.
 *
 * detail을 따로 받는 이유: Error에 적힌 메시지는 일반화된 문구라서 어느 데이터가
 * 문제였는지가 안 드러남. "데이터를 찾을 수 없습니다"만 보고는 뭘 못 찾았는지 모름.
 */
@Getter
public class ResponseException extends RuntimeException {

    private final Error error;

    public ResponseException(Error error) {
        super(error.getMessage());
        this.error = error;
    }

    public ResponseException(Error error, String detail) {
        super(error.getMessage() + " (" + detail + ")");
        this.error = error;
    }

    /** 원인 예외가 있을 때. 체인을 끊지 말 것 — 로그에서 진짜 원인을 잃어버림 */
    public ResponseException(Error error, String detail, Throwable cause) {
        super(error.getMessage() + " (" + detail + ")", cause);
        this.error = error;
    }
}
