package com.skala.planbmarket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화.
 *
 * 기본 스케줄러 스레드는 1개라 작업이 순차로 돌아감. 지금은 그게 오히려 나음 —
 * 만료 처리와 예약 정리가 동시에 같은 판매 건을 건드리면 상태가 꼬일 수 있는데,
 * 한 줄로 세워두면 그 경합이 아예 안 생김.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
