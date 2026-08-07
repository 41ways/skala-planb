package com.skala.planbmarket.scheduler;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skala.planbmarket.service.ExpiryService;

import lombok.RequiredArgsConstructor;

/**
 * 티켓 소멸 스케줄러.
 *
 * 이 프로젝트에서 시간을 실제로 흐르게 하는 장치임. 아무도 아무것도 안 해도
 * 여기서 자산이 사라지고 묶여 있던 돈이 제자리로 돌아감.
 *
 * 1분 주기인 이유: 만료 시각은 초 단위로 정해져 있는데 처리가 늦어지면 그 사이에
 * 이미 만료된 티켓을 예약하거나 결제할 수 있게 됨. 물론 서비스 쪽에서도 매번 만료를
 * 확인하지만, 상태가 오래 어긋나 있으면 목록에 "살 수 있는 것처럼" 계속 보임.
 */
@Component
@RequiredArgsConstructor
public class ExpiryScheduler {

    /** 만료 몇 시간 전에 경고할지 */
    private static final long WARN_BEFORE_HOURS = 24;

    private final ExpiryService expiryService;

    @Scheduled(fixedRate = 60_000)
    public void run() {
        LocalDateTime now = LocalDateTime.now();

        // 경고를 먼저 보냄. 순서가 반대면 방금 실효 처리된 티켓에 대고
        // "곧 만료됩니다"라는 알림이 나가는 우스운 상황이 생김
        expiryService.warnExpiringTickets(now, WARN_BEFORE_HOURS);
        expiryService.expireTickets(now);
    }
}
