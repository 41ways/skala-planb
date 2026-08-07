package com.skala.planbmarket.common;

import org.springframework.stereotype.Component;

import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 상태를 다루는 창구. 교재의 SessionHandler 추상화를 그대로 유지하고 안쪽만 세션으로 구현.
 *
 * 서비스·컨트롤러는 "지금 누가 로그인했나"만 물어보고, 그게 세션인지 토큰인지는 모름.
 * 나중에 JWT로 갈아끼울 일이 생겨도 이 클래스 안만 고치면 되게 하려는 것.
 * (이번 과제는 JWT 미사용이라 실제로 갈아끼우진 않음)
 *
 * HttpServletRequest를 주입받는데, 스프링이 요청마다 실제 객체로 연결해주는 프록시를 넣어줌.
 * 그래서 컨트롤러마다 HttpSession을 파라미터로 끌고 다니지 않아도 됨.
 */
@Component
@RequiredArgsConstructor
public class SessionHandler {

    private static final String LOGIN_KEY = "LOGIN_MEMBER_ID";

    private final HttpServletRequest request;

    public void login(String memberId) {
        request.getSession(true).setAttribute(LOGIN_KEY, memberId);
    }

    public void logout() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 로그인 안 했으면 null. 공개 API에서 "내가 대기 중인가" 같은 걸 볼 때 씀 */
    public String getLoginMemberId() {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        return (String) session.getAttribute(LOGIN_KEY);
    }

    /** 로그인 필수 API용. 안 했으면 401 */
    public String requireLoginMemberId() {
        String memberId = getLoginMemberId();
        if (memberId == null) {
            throw new ResponseException(Error.NOT_AUTHENTICATED);
        }
        return memberId;
    }

    /**
     * 본인 것인지 확인. 원장·알림·내 거래처럼 남이 보면 안 되는 조회에 씀.
     *
     * 로그인 여부(401)와 남의 것(403)을 구분해서 던지는 게 중요함. 둘 다 403으로 뭉개면
     * 로그인이 풀린 건지 권한이 없는 건지 사용자가 알 수 없어서 대응할 방법이 없음.
     */
    public String requireSelf(String targetMemberId) {
        String memberId = requireLoginMemberId();
        if (!memberId.equals(targetMemberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 것만 접근할 수 있습니다");
        }
        return memberId;
    }
}
