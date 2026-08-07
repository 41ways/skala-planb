package com.skala.planbmarket.scheduler;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skala.planbmarket.service.ExpiryService;

import lombok.RequiredArgsConstructor;

/**
 * 자동 확정 스케줄러.
 *
 * 구매자가 확정 버튼을 안 눌러도 판매자가 영영 돈을 못 받는 일은 없어야 함.
 *
 * 5분 주기로 둔 이유: 자동 확정 시각을 만료보다 10분 앞당겨 잡아뒀기 때문에,
 * 최악의 경우 5분 늦게 처리돼도 아직 만료 5분 전임. 만료 스케줄러와 같은 건을 놓고
 * 부딪히는 일이 구조적으로 안 생김. 1분마다 돌 이유가 없어서 부하만 줄였음.
 */
@Component
@RequiredArgsConstructor
public class AutoConfirmScheduler {

    private final ExpiryService expiryService;

    @Scheduled(fixedRate = 300_000)
    public void run() {
        expiryService.autoConfirmDue(LocalDateTime.now());
    }
}
