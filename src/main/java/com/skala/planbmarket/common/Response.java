package com.skala.planbmarket.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;

/**
 * 공통 응답 포맷.
 *
 * 성공:  { "result": "SUCCESS", "resultCode": 200, "body": { ... } }
 * 실패:  { "result": "FAILURE", "resultCode": 400, "message": "...", "errors": { ... } }
 *
 * NON_NULL이라 안 쓰는 필드는 아예 안 나감. 성공 응답에 message: null이 붙어 있으면
 * 프런트에서 "메시지가 있나?" 하고 한 번 더 확인하게 되는데 그럴 필요 없게 함.
 *
 * resultCode는 HTTP 상태 코드와 같은 값을 넣음. 둘이 다르면 어느 쪽을 믿어야 할지
 * 애매해지고, 실제로 그 불일치 때문에 디버깅이 어려워지는 경우가 많음.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Response<T> {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    private final String result;
    private final int resultCode;
    private final T body;
    private final String message;
    private final Map<String, String> errors;

    private Response(String result, int resultCode, T body, String message, Map<String, String> errors) {
        this.result = result;
        this.resultCode = resultCode;
        this.body = body;
        this.message = message;
        this.errors = errors;
    }

    public static <T> Response<T> success(T body) {
        return new Response<>(SUCCESS, 200, body, null, null);
    }

    /** 돌려줄 게 없는 성공 (로그아웃, 삭제 등) */
    public static Response<Void> success() {
        return new Response<>(SUCCESS, 200, null, null, null);
    }

    public static <T> Response<T> created(T body) {
        return new Response<>(SUCCESS, 201, body, null, null);
    }

    public static Response<Void> failure(int resultCode, String message) {
        return new Response<>(FAILURE, resultCode, null, message, null);
    }

    public static Response<Void> failure(int resultCode, String message, Map<String, String> errors) {
        return new Response<>(FAILURE, resultCode, null, message, errors);
    }
}
