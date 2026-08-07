package com.skala.planbmarket.exception;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.skala.planbmarket.common.Response;

import lombok.extern.slf4j.Slf4j;

/**
 * 예외를 응답 포맷으로 바꿔주는 곳.
 *
 * 여기서 Exception 하나로 다 잡아버리면 500만 잔뜩 나가고 원인을 못 찾음.
 * 그래서 원인별로 나눠 잡고, 마지막 Exception 핸들러는 정말 예상 못 한 것만 받게 둠.
 * 그 마지막 핸들러에 로그가 찍혔다는 건 곧 "처리 안 한 케이스를 발견했다"는 뜻이라
 * 스택트레이스를 통째로 남김.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 업무 규칙 위반 */
    @ExceptionHandler(ResponseException.class)
    public ResponseEntity<Response<Void>> handleResponse(ResponseException e) {
        Error error = e.getError();
        log.debug("업무 예외: {} - {}", error.name(), e.getMessage());
        return ResponseEntity.status(error.getStatus())
                .body(Response.failure(error.getStatus(), e.getMessage()));
    }

    /** 애노테이션으로 표현 못 하는 검증 실패 */
    @ExceptionHandler(ParameterException.class)
    public ResponseEntity<Response<Void>> handleParameter(ParameterException e) {
        return ResponseEntity.badRequest()
                .body(Response.failure(400, e.getMessage(), e.getErrors()));
    }

    /**
     * @Valid 실패. 필드별로 다 모아서 한 번에 돌려줌.
     *
     * 첫 번째 오류만 주면 사용자가 고치고 다시 보내고, 또 다른 오류가 나오고를 반복함.
     * 한 번에 다 보여줘야 한 번에 고침.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (org.springframework.validation.FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            // 같은 필드에 오류가 여럿이면 첫 번째만. 다 보여주면 오히려 뭘 고쳐야 할지 흐려짐
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        for (org.springframework.validation.ObjectError globalError : e.getBindingResult().getGlobalErrors()) {
            errors.putIfAbsent(globalError.getObjectName(), globalError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(Response.failure(400, "입력값이 올바르지 않습니다", errors));
    }

    /** enum에 없는 값이나 숫자 자리에 문자가 온 경우 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = (e.getRequiredType() == null) ? "알 수 없음" : e.getRequiredType().getSimpleName();
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(e.getName(), "'" + e.getValue() + "'는 처리할 수 없는 값입니다 (필요한 타입: " + expected + ")");
        return ResponseEntity.badRequest()
                .body(Response.failure(400, "입력값이 올바르지 않습니다", errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(e.getParameterName(), "필수 파라미터입니다");
        return ResponseEntity.badRequest()
                .body(Response.failure(400, "입력값이 올바르지 않습니다", errors));
    }

    /**
     * 본문 JSON이 깨졌거나 값 타입이 안 맞는 경우.
     *
     * 여기서 그냥 "본문을 읽을 수 없습니다"로 끝내면 어느 필드가 문제인지 알 수가 없음.
     * 특히 enum에 오타를 내는 건 흔한 실수인데, 잭슨이 역직렬화 단계에서 터뜨려서
     * @Valid까지 가지도 못함. 그래서 예외 안에 든 경로와 후보값을 꺼내 필드 오류로 바꿔줌.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidFormatException cause) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put(fieldPathOf(cause), describeInvalidValue(cause));
            return ResponseEntity.badRequest()
                    .body(Response.failure(400, "입력값이 올바르지 않습니다", errors));
        }
        log.debug("요청 본문을 읽을 수 없음", e);
        return ResponseEntity.badRequest()
                .body(Response.failure(400, "요청 본문을 읽을 수 없습니다"));
    }

    /** 중첩 객체까지 고려해 "a.b.c" 형태로 만듦 */
    private String fieldPathOf(InvalidFormatException cause) {
        StringBuilder path = new StringBuilder();
        for (InvalidFormatException.Reference reference : cause.getPath()) {
            if (reference.getFieldName() == null) {
                continue;
            }
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(reference.getFieldName());
        }
        return path.length() == 0 ? "body" : path.toString();
    }

    private String describeInvalidValue(InvalidFormatException cause) {
        Class<?> targetType = cause.getTargetType();
        if (targetType != null && targetType.isEnum()) {
            String candidates = Arrays.stream(targetType.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", "));
            return "'" + cause.getValue() + "'는 쓸 수 없는 값입니다 (가능한 값: " + candidates + ")";
        }
        String expected = (targetType == null) ? "알 수 없음" : targetType.getSimpleName();
        return "'" + cause.getValue() + "'는 처리할 수 없는 값입니다 (필요한 형식: " + expected + ")";
    }

    /** 여기 걸렸다는 건 처리 안 한 케이스가 있다는 뜻이라 통째로 남김 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Response.failure(500, "서버 오류가 발생했습니다"));
    }
}
