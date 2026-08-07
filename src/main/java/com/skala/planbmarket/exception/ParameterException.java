package com.skala.planbmarket.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;

/**
 * 입력 검증 실패. 필드별로 뭐가 틀렸는지 담아서 던짐.
 *
 * Bean Validation(@NotBlank 등)으로 잡히는 건 GlobalExceptionHandler가 알아서 처리하고,
 * 이건 애노테이션으로 표현 못 하는 검증에 씀. 예를 들어 "POINT_IN_TIME 카테고리인데
 * eventAt이 비었다" 같은 건 카테고리를 봐야 알 수 있어서 애노테이션으론 안 됨.
 *
 * 순서가 보이게 LinkedHashMap을 씀. 응답에서 필드가 뒤죽박죽 나오면 읽기 불편함.
 */
@Getter
public class ParameterException extends RuntimeException {

    private final Map<String, String> errors;

    public ParameterException(String field, String message) {
        super("입력값이 올바르지 않습니다");
        this.errors = new LinkedHashMap<>();
        this.errors.put(field, message);
    }

    public ParameterException(Map<String, String> errors) {
        super("입력값이 올바르지 않습니다");
        this.errors = new LinkedHashMap<>(errors);
    }
}
