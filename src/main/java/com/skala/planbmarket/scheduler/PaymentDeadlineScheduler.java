package com.skala.planbmarket.scheduler;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skala.planbmarket.service.EscrowService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 제한시간 스케줄러 — 마감 임박 경고와 초과 예약 정리.
 *
 * 정리가 없으면 예약을 걸어놓고 사라진 판매 건이 영영 RESERVED에 묶여서 아무도 못 삼.
 * 소멸성 자산이라 그렇게 묶여 있는 동안에도 티켓은 계속 만료를 향해 가고, 판매자만 손해임.
 *
 * 1분 주기인 이유: 결제 제한시간이 짧으면 몇 분 단위라 이보다 뜸하면 이미 시한이 지난
 * 예약이 한참 살아 있게 됨. 대신 사용자가 결제를 누르는 시점에도 EscrowService.pay()가
 * 다시 시한을 확인해서, 스케줄러가 돌기 전 틈으로 결제가 새는 걸 막음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentDeadlineScheduler {

    private final EscrowService escrowService;

    @Scheduled(fixedRate = 60_000)
    public void run() {
        LocalDateTime now = LocalDateTime.now();

        // 경고를 먼저. 순서가 반대면 방금 몰수된 예약에 "곧 마감입니다" 알림이 나감
        int warned = escrowService.warnUpcomingDeadlines(now);
        int expired = escrowService.expireOverdueReservations(now);

        if (warned > 0) {
            log.info("결제 마감 임박 경고 {}건 발송", warned);
        }
        if (expired > 0) {
            log.info("결제 제한시간 초과 예약 {}건 몰수 처리", expired);
        }
    }
}
