package com.skala.planbmarket.aop;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * API 요청·응답·처리시간 로깅.
 *
 * 컨트롤러마다 로그를 넣으면 같은 코드가 27번 반복되고, 하나 빠뜨려도 아무도 모른다.
 * 로깅은 업무 로직이 아니라 <b>모든 요청에 공통으로 얹히는 관심사</b>라
 * 한 곳에서 가로채는 게 맞다. 이게 AOP를 쓰는 교과서적인 자리다.
 *
 * <p><b>비밀번호 마스킹.</b> 요청 본문을 그대로 찍으면 회원가입·로그인의 평문 비밀번호가
 * 로그에 남는다. 이 프로젝트는 비밀번호를 평문 저장하므로 더더욱 로그에까지 남길 이유가 없다.
 * DTO가 record라 {@code toString()}이 자동 생성되는데, 거기서 password 값만 지운다.
 *
 * <p>실패도 같이 찍는다. 성공만 찍으면 예외가 난 요청은 로그에 흔적이 없어서
 * "요청이 안 왔나 처리하다 터졌나"를 구분할 수 없다.
 */
@Slf4j
@Aspect
@Component
public class ApiLogAspect {

    /** 값이 무엇이든 password= 뒤를 가린다. record toString은 {@code Login[id=x, password=y]} 꼴 */
    private static final Pattern PASSWORD = Pattern.compile("password=[^,)\\]]*");

    /** 응답이 길면 로그가 화면을 덮는다. 무엇이 왔는지 알아볼 정도만 남긴다 */
    private static final int MAX_RESULT_LENGTH = 200;

    @Pointcut("within(com.skala.planbmarket.controller..*)")
    public void controllerLayer() {
    }

    @Around("controllerLayer()")
    public Object logApi(ProceedingJoinPoint joinPoint) throws Throwable {
        String signature = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        String args = mask(Arrays.toString(joinPoint.getArgs()));

        log.info("[API] → {} {}  {}", httpMethod(), requestUri(), signature);
        log.debug("[API]   args {}", args);

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("[API] ← {} {}  {}ms  {}", httpMethod(), requestUri(), ms, summarize(result));
            return result;
        } catch (Throwable e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            // 예외 자체는 GlobalExceptionHandler가 응답으로 바꾼다. 여기선 "어느 요청에서
            // 무엇이 터졌는지"만 남기고 그대로 흘려보낸다 — 삼키면 응답이 사라진다
            log.warn("[API] ✗ {} {}  {}ms  {}: {}",
                    httpMethod(), requestUri(), ms, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private String mask(String text) {
        return PASSWORD.matcher(text).replaceAll("password=****");
    }

    private String summarize(Object result) {
        if (result == null) {
            return "null";
        }
        String text = mask(String.valueOf(result));
        return text.length() <= MAX_RESULT_LENGTH
                ? text
                : text.substring(0, MAX_RESULT_LENGTH) + "…(" + text.length() + "자)";
    }

    private String httpMethod() {
        HttpServletRequest request = currentRequest();
        return request == null ? "-" : request.getMethod();
    }

    private String requestUri() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "-";
        }
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    /**
     * 지금 처리 중인 요청. 없으면 null.
     *
     * 스케줄러나 동시성 시뮬레이터의 스레드에서 컨트롤러 빈이 불릴 일은 없지만,
     * 요청 스코프에 기대는 코드는 "요청이 없을 수도 있다"를 항상 다뤄야 안전하다.
     */
    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest()
                : null;
    }
}
