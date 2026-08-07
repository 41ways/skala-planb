package com.skala.planbmarket.dto.response;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 일별 실효 손실.
 *
 * 이 프로젝트가 무엇을 다루는지를 숫자 하나로 보여주는 응답임 —
 * <b>아무도 아무 잘못을 안 했는데 매일 얼마씩 사라진다.</b>
 *
 * <p>실효가 없던 날은 행이 안 나옴. 0인 날까지 채우는 건 그리는 쪽(대시보드) 일이라
 * API는 있었던 사실만 돌려줌. 여기서 빈 날을 만들어 내보내면 "조회 기간"과 "데이터"가
 * 섞여서, 나중에 기간 규칙이 바뀔 때 API와 화면 양쪽을 고쳐야 함.
 */
@Schema(name = "일별 실효 손실 응답")
public record ExpiryLossResponse(

        @Schema(description = "조회 기간(일)")
        int days,

        @Schema(description = "이 시각 이후 만료된 건만 집계")
        LocalDate since,

        List<Row> daily,

        Totals totals
) {

    @Schema(name = "일별 실효 한 줄")
    public record Row(

            @Schema(description = "실효된 날짜 (티켓 만료 시각 기준)")
            LocalDate lossDate,

            long expiredCount,

            @Schema(description = "그중 판매 등록까지 갔던 건수")
            long listedCount,

            @Schema(description = "정가 기준 손실액 — 얼마짜리가 버려졌나")
            long originalLoss,

            @Schema(description = "시장가(희망가) 기준 손실액 — 실제로 얼마에 팔릴 수 있었나")
            long marketLoss
    ) {
    }

    @Schema(name = "실효 손실 합계")
    public record Totals(
            long expiredCount,
            long listedCount,
            long originalLoss,
            long marketLoss,

            @Schema(description = "판매 등록조차 안 하고 소멸한 건수 — 시장에 나오지도 못한 손실")
            long neverListedCount
    ) {
    }
}
