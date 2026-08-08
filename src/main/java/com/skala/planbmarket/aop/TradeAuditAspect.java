package com.skala.planbmarket.aop;

import java.util.Arrays;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 금전 이동 감사 로그.
 *
 * 돈이 움직이는 서비스 호출을 한 곳에서 가로채 "누가 무엇을 언제" 를 남긴다.
 * 감사 로그는 업무 로직이 아니라 <b>사후에 되짚기 위한 기록</b>이라, 로직에 섞으면
 * 읽기도 나빠지고 빠뜨리기도 쉽다.
 *
 * <h2>이 Aspect가 카운터를 올리지 않는 이유 ⭐</h2>
 *
 * SPEC 7장은 여기서 Micrometer 카운터를 올리라고 했다. 그렇게 하면 <b>숫자가 틀린다.</b>
 * 스프링 AOP는 프록시 기반이라 <b>public 메서드의 외부 호출만</b> 가로챈다.
 * 그런데 이 도메인에서 확정·무산은 상당수가 그 바깥에서 일어난다:
 *
 * <ul>
 *   <li>{@code EscrowService.settle()} — package-private. {@code confirm()}이 클래스
 *       내부에서 부르고, 자동확정 스케줄러도 이 메서드로 들어온다. <b>둘 다 안 잡힌다.</b></li>
 *   <li>{@code EscrowService.voidEscrow()} — 만료 스케줄러 경로. 역시 안 잡힌다.</li>
 * </ul>
 *
 * 즉 AOP로만 세면 <b>사람이 직접 누른 확정만 잡히고, 시간이 알아서 처리한 확정은
 * 통째로 빠진다.</b> "시간이 모든 것을 지배한다"는 게 이 프로젝트의 컨셉인데
 * 그 부분이 지표에서 사라지면 지표가 현실을 반영하지 못한다.
 *
 * <p>그래서 도메인 카운터는 사건이 실제로 일어나는 자리에서 직접 올리고
 * ({@code PlanbMetrics}), 여기서는 <b>"AOP가 무엇을 봤는가"</b>만 따로 센다
 * ({@code planb.audit.intercepted}). 두 숫자를 나란히 보면 프록시 한계가 그대로 드러난다:
 *
 * <pre>
 *   planb.escrow.confirmed        3   ← 실제 확정 (직접 확정 + 자동 확정)
 *   planb.audit.intercepted{...}  1   ← AOP가 본 것 (직접 확정만)
 * </pre>
 *
 * 8단계에서도 같은 함정에 실제로 걸렸다 — {@code reconcileBalances()}를 클래스 내부에서
 * 부르니 {@code @Transactional}이 안 걸려 "고쳤다는데 안 고쳐지는" 증상이 났다.
 * 프록시 한계는 트랜잭션과 AOP 양쪽에 똑같이 적용된다.
 */
@Slf4j
@Aspect
@Component
public class TradeAuditAspect {

    private final MeterRegistry registry;

    public TradeAuditAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 돈이 움직이는 서비스들.
     *
     * {@code execution(public ...)}을 명시한 건 장식이 아니다. 프록시가 어차피 public만
     * 잡는다는 사실을 포인트컷에 드러내 둬야, 나중에 "왜 settle()은 안 찍히지?"를
     * 코드만 보고 알 수 있다.
     */
    @Pointcut("execution(public * com.skala.planbmarket.service.EscrowService.*(..)) "
            + "|| execution(public * com.skala.planbmarket.service.DepositService.*(..)) "
            + "|| execution(public * com.skala.planbmarket.service.LedgerService.transfer(..))")
    public void moneyMoving() {
    }

    @Around("moneyMoving()")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        String type = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String method = joinPoint.getSignature().getName();

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long ms = (System.nanoTime() - start) / 1_000_000;

            log.info("[AUDIT] {}.{}  args={}  {}ms", type, method,
                    Arrays.toString(joinPoint.getArgs()), ms);
            intercepted(type, method, "ok");
            return result;
        } catch (Throwable e) {
            // 거절도 남긴다. 성공만 남기면 "왜 이 사람은 결제를 못 했나"를 되짚을 수 없다
            log.info("[AUDIT] {}.{}  거절 — {}: {}", type, method,
                    e.getClass().getSimpleName(), e.getMessage());
            intercepted(type, method, "failed");
            throw e;
        }
    }

    /**
     * AOP가 가로챈 호출 수. 도메인 카운터와 대조하기 위한 것이지 도메인 지표가 아니다.
     *
     * 태그를 미리 다 만들어두지 않고 호출 시점에 등록한다 — 어차피 종류가 몇 개 안 되고,
     * "실제로 가로채진 것만 나타난다"는 성질 자체가 이 지표의 요점이다.
     */
    private void intercepted(String type, String method, String outcome) {
        Counter.builder("planb.audit.intercepted")
                .description("AOP가 실제로 가로챈 금전 이동 호출 — 프록시 한계 대조용")
                .tag("service", type)
                .tag("method", method)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
