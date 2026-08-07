package com.skala.planbmarket.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 동시성 테스트 결과.
 *
 * 같은 판매 건에 N개 스레드가 동시에 예약을 건다. 바뀌는 건 조회에 락을 거느냐 뿐이고
 * 나머지 코드 경로는 완전히 같다 — 그래야 "락 말고 다른 게 달랐던 것 아니냐"에 답할 수 있다.
 *
 * <p><b>{@code ledgerBalanced}를 같이 내보내는 이유가 이 응답의 핵심이다.</b>
 * 락 없이 돌려서 예약이 7건 생겨도 <b>원장은 멀쩡하다.</b> 홀드 하나하나가 제대로
 * 2줄씩 기록됐기 때문이다. 즉 정합성 검증은 이 버그를 <b>못 잡는다.</b>
 *
 * <p>돈이 맞는 것과 도메인 규칙이 지켜지는 것은 다른 문제다. 원장은 "돈이 새지
 * 않았나"를 보고, 락은 "한 물건이 한 명에게만 가는가"를 지킨다. 어느 하나가
 * 다른 하나를 대신해주지 않는다.
 */
@Schema(name = "동시성 테스트 응답")
public record ConcurrencyTestResponse(

        Long listingId,
        int threadCount,

        @Schema(description = "비관적 락을 걸었는지")
        boolean useLock,

        @Schema(description = "예약에 성공한 요청 수. 락이 있으면 반드시 1이어야 함")
        int success,

        @Schema(description = "거절된 요청 수")
        int failed,

        @Schema(description = "실제로 생성된 예약(HELD) 건수 — 결과 판정의 근거")
        long reservationCount,

        @Schema(description = "판매 건 하나에 예약이 하나뿐인가. false면 중복 예약이 발생한 것")
        boolean dataIntegrity,

        @Schema(description = """
                원장 차대가 맞는가. 중복 예약이 생겨도 여기는 true다 —
                홀드마다 원장 2줄이 제대로 남기 때문. 정합성 검증이 못 잡는 종류의 버그라는 뜻""")
        boolean ledgerBalanced,

        @Schema(description = """
                회원 잔액이 원장 합과 일치하는가. 락이 없으면 여기도 깨진다 —
                두 스레드가 같은 잔액을 읽고 각자 뺀 값을 써서 앞의 차감이 덮어써짐(lost update).
                SPEC 5-1의 세 번째 경합 지점이 실제로 일어난 자리""")
        boolean balanceIntegrity,

        @Schema(description = """
                잔액이 어긋나 있던 회원과 금액. 되돌려 놓았지만 무슨 일이 있었는지는 남긴다 —
                조용히 고치면 실험이 증명한 것을 지우는 셈이라서""")
        List<String> lostUpdates,

        @Schema(description = "거절 사유별 집계. 락을 걸면 ALREADY_RESERVED로 모임")
        List<FailureCount> failures,

        @Schema(description = "전체 소요 시간(ms)")
        long elapsedMs,

        @Schema(description = """
                뒷정리로 되돌린 예약 수. 락 없이 만든 중복 예약과 어긋난 잔액을 그냥 두면
                정합성 검증이 영구히 실패해서 시연을 한 번밖에 못 함.
                예약은 전액 환불로, 잔액은 원장 합계에 맞춰 되돌림""")
        int cleanedUp,

        String message
) {

    @Schema(name = "거절 사유 집계")
    public record FailureCount(
            @Schema(description = "에러 코드 또는 예외 이름") String reason,
            int count
    ) {
    }
}
