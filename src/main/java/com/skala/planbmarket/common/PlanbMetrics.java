package com.skala.planbmarket.common;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 도메인 이벤트 카운터.
 *
 * <p><b>왜 AOP가 아니라 서비스에서 직접 올리는가.</b> SPEC 7장은 {@code TradeAuditAspect}가
 * 카운터를 올리라고 했는데, 그렇게 하면 <b>숫자가 틀린다.</b> 스프링 AOP는 프록시 기반이라
 * public 메서드의 외부 호출만 가로챈다. 그런데 이 도메인의 확정·무산은 상당수가
 * 그 바깥에서 일어난다:
 *
 * <ul>
 *   <li>{@code EscrowService.settle()} — package-private이고 {@code confirm()}이
 *       클래스 내부에서 부른다. 자동확정 스케줄러가 부를 때도 마찬가지.</li>
 *   <li>{@code EscrowService.voidEscrow()} — 만료 스케줄러가 부른다.</li>
 * </ul>
 *
 * 즉 AOP로만 세면 <b>구매자가 직접 누른 확정만 잡히고 자동확정은 통째로 빠진다.</b>
 * "시간이 알아서 처리하는" 게 이 도메인의 성질인데, 그 부분이 메트릭에서 사라지면
 * 지표가 현실을 반영하지 못한다.
 *
 * <p>그래서 <b>도메인 이벤트가 실제로 일어나는 자리</b>에서 직접 올린다.
 * AOP는 감사 로그와 "무엇을 못 잡는가"를 보여주는 역할로 남겼다
 * ({@code TradeAuditAspect} 참조). 둘을 나란히 보면 프록시 한계가 숫자로 드러난다.
 */
@Component
public class PlanbMetrics {

    private final Counter reservationCreated;
    private final Counter escrowCreated;
    private final Counter escrowConfirmed;
    private final Counter depositForfeited;
    private final Counter ticketExpired;

    public PlanbMetrics(MeterRegistry registry) {
        this.reservationCreated = Counter.builder("planb.reservation.created")
                .description("구매 예약 생성 (예약금 홀드)")
                .register(registry);

        this.escrowCreated = Counter.builder("planb.escrow.created")
                .description("본결제 완료로 에스크로 생성")
                .register(registry);

        this.escrowConfirmed = Counter.builder("planb.escrow.confirmed")
                .description("거래 확정 — 구매자 확정과 자동 확정을 모두 포함")
                .register(registry);

        this.depositForfeited = Counter.builder("planb.deposit.forfeited")
                .description("예약금 몰수")
                .register(registry);

        // SPEC의 planb.pair.matched는 없다. 1매 대기를 5단계에서 걷어내면서
        // 셀 사건 자체가 사라졌다. 안 쓰는 지표를 0으로 남겨두면 "왜 늘 0이냐"만 묻게 된다.
        // 대신 새 흐름의 첫 관문인 예약 생성을 planb.reservation.created로 센다.
        this.ticketExpired = Counter.builder("planb.ticket.expired")
                .description("티켓 실효 — 아무도 못 쓰고 소멸한 건수")
                .register(registry);
    }

    public void reservationCreated() {
        reservationCreated.increment();
    }

    public void escrowCreated() {
        escrowCreated.increment();
    }

    public void escrowConfirmed() {
        escrowConfirmed.increment();
    }

    public void depositForfeited() {
        depositForfeited.increment();
    }

    public void ticketExpired(int count) {
        if (count > 0) {
            ticketExpired.increment(count);
        }
    }
}
