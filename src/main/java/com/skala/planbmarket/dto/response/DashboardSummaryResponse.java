package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대시보드 상단 요약.
 *
 * <p>이 API가 <b>MyBatis가 아니라 JPA</b>인 이유는 12절 경계 기준 그대로다 —
 * 여기 있는 건 전부 독립적인 건수·합계이고, 여러 행을 구간으로 나눠 접는 작업이 아니다.
 * 카운트 쿼리 몇 개를 나란히 부르는 것뿐이라 GROUP BY가 등장하지 않는다.
 * 카테고리별 현황(MyBatis)과 나란히 놓으면 그 차이가 잘 드러난다.
 *
 * <p>숫자를 고를 때 기준은 <b>"가만히 두면 손해가 나는 것"</b>이었다.
 * 이 도메인은 사용자가 아무것도 안 해도 상태가 나빠지는 쪽으로만 흐른다.
 * 그래서 총 거래액 같은 누적 지표보다 <b>지금 위험한 것</b>이 위로 온다.
 */
@Schema(name = "대시보드 요약 응답")
public record DashboardSummaryResponse(

        @Schema(description = "24시간 안에 소멸하는 판매 중 티켓 — 가장 급한 숫자")
        long expiringSoonCount,

        @Schema(description = "오늘 실효된 티켓 수")
        long expiredTodayCount,

        @Schema(description = "오늘 실효로 사라진 정가 합 — 아무도 잘못하지 않았는데 사라진 돈")
        long expiredTodayLoss,

        @Schema(description = "지금 예약금이 걸려 결제를 기다리는 건수")
        long reservedCount,

        @Schema(description = "결제 완료로 에스크로에 묶여 있는 건수")
        long inEscrowCount,

        @Schema(description = "구매 가능한 판매 건수")
        long openListingCount,

        @Schema(description = "누적 확정 거래액")
        long totalTradedAmount,

        @Schema(description = "누적 확정 거래 건수")
        long completedCount,

        @Schema(description = "누적 실효 건수")
        long expiredTotalCount,

        @Schema(description = "플랫폼 누적 수익 (중개 수수료 + 몰수분)")
        long platformBalance,

        @Schema(description = "지금 에스크로에 묶여 있는 금액")
        long escrowPoolBalance,

        @Schema(description = "지금 예약금으로 홀드된 금액")
        long depositPoolBalance,

        LocalDateTime checkedAt
) {
}
