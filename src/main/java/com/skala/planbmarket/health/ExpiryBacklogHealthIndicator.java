package com.skala.planbmarket.health;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.skala.planbmarket.domain.enums.TicketStatus;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 만료 처리가 밀리고 있는지 본다.
 *
 * <p><b>왜 이게 이 프로젝트의 헬스체크인가.</b> 보통 헬스체크는 "DB에 붙는가" 같은
 * 기반 상태를 본다. 그런데 이 도메인에서 진짜 위험한 고장은 <b>스케줄러가 조용히 멈추는 것</b>이다.
 * 앱은 멀쩡히 응답하고 API도 다 되는데 티켓만 실효되지 않는다. 그러면 이미 지나간
 * 티켓이 계속 팔리고, 예약금이 묶인 채로 남고, 아무도 이상을 눈치채지 못한다.
 * <b>죽은 채로 살아 있는 것처럼 보이는 고장</b>이라 밖에서 봐서는 안 보인다.
 *
 * <p><b>임계값을 5로 잡은 근거.</b> 스케줄러가 1분 주기라 <b>정상 상태에서도</b>
 * 방금 만료된 티켓이 잠깐 백로그에 있는다. 1건에서 DOWN을 내면 주기 사이마다
 * 깜빡여서 아무도 안 믿는 지표가 된다. 반대로 너무 크게 잡으면 정작 멈췄을 때
 * 한참 뒤에야 알게 된다. 시드가 활성 티켓 20건 규모인 걸 감안해 5건으로 뒀다 —
 * 1분 안에 5건이 동시에 만료되는 건 이 규모에서 정상적인 일이 아니다.
 *
 * <p>실서비스라면 이 값은 <b>거래량에 비례해야</b> 한다. 고정 건수가 아니라
 * "평소 1분당 만료 건수의 N배" 같은 상대 기준이 맞다. 시드 규모가 고정된
 * 이번 범위에서는 고정값으로 충분해서 단순하게 뒀다.
 */
@Component("expiryBacklog")
@RequiredArgsConstructor
public class ExpiryBacklogHealthIndicator implements HealthIndicator {

    /** 이 건수부터 DOWN */
    private static final long BACKLOG_THRESHOLD = 5;

    /** 아직 살아 있는(실효 처리 대상) 상태들 */
    private static final List<TicketStatus> ALIVE =
            List.of(TicketStatus.OWNED, TicketStatus.LISTED);

    private final TicketRepository ticketRepository;

    @Override
    public Health health() {
        long backlog = ticketRepository.countByStatusInAndExpiresAtBefore(ALIVE, LocalDateTime.now());

        Health.Builder builder = backlog >= BACKLOG_THRESHOLD ? Health.down() : Health.up();
        return builder
                .withDetail("backlog", backlog)
                .withDetail("threshold", BACKLOG_THRESHOLD)
                .withDetail("meaning", "만료 시각이 지났는데 아직 실효 처리가 안 된 티켓 수")
                .withDetail("hint", backlog >= BACKLOG_THRESHOLD
                        ? "ExpiryScheduler가 멈췄거나 처리가 막혔는지 확인할 것"
                        : "스케줄러 1분 주기라 소수는 정상")
                .build();
    }
}
