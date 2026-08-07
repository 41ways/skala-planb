package com.skala.planbmarket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.dto.response.CategorySummaryResponse;
import com.skala.planbmarket.dto.response.ExpiryLossResponse;
import com.skala.planbmarket.dto.response.PriceSuggestionResponse;
import com.skala.planbmarket.service.AnalysisService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 분석·통계 API (MyBatis).
 *
 * 셋 다 공개 조회임. 개인 정보가 아니라 시장 전체의 집계라 감출 이유가 없고,
 * 가격 추천은 오히려 판매 등록 <b>전에</b> 봐야 쓸모가 있어서 로그인을 요구하면
 * 쓸 자리를 잃음.
 */
@Tag(name = "6. 분석·통계", description = "가격 추천, 카테고리 현황, 실효 손실 (MyBatis)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @Operation(summary = "가격 추천",
            description = """
                    같은 카테고리 + 같은 잔여시간 구간의 최근 30일 확정 거래에서
                    평균 거래가율(거래가 / 정가)을 구해 내 티켓 정가에 곱한다.

                    표본이 없으면 카테고리 전체 평균으로, 그것도 없으면 정가의 70%로 내려간다.
                    어느 단계에서 나온 값인지는 basis, 표본이 몇 건이었는지는 sampleCount로 확인할 것.

                    category 파라미터는 받지 않는다 — 티켓에서 그대로 나오는 값이라,
                    따로 받으면 "둘이 어긋나면 어느 쪽을 믿나"라는 분기가 공짜로 생긴다.""")
    @GetMapping("/price-suggestion")
    public Response<PriceSuggestionResponse> priceSuggestion(@RequestParam Long ticketId) {
        return Response.success(analysisService.suggestPrice(ticketId));
    }

    @Operation(summary = "카테고리별 거래 현황",
            description = """
                    카테고리마다 등록·양도·실효 건수와 금액을 집계한다.
                    실효율의 분모는 "결말이 난 티켓"(양도완료 + 실효) — 아직 판매 중인 매물은 빠진다.

                    티켓이 한 건도 없는 카테고리도 0으로 채워서 8줄이 항상 나온다.""")
    @GetMapping("/category-summary")
    public Response<CategorySummaryResponse> categorySummary() {
        return Response.success(analysisService.categorySummary());
    }

    @Operation(summary = "일별 실효 손실",
            description = """
                    최근 N일간 소멸한 티켓을 날짜별로 집계한다. 날짜는 티켓의 만료 시각 기준.

                    손실을 정가와 시장가 두 갈래로 낸다. 판매 등록조차 안 하고 썩힌 티켓은
                    시장가가 0이라, 두 숫자의 차이가 "시장에 나오지도 못한 손실"을 드러낸다.

                    실효가 없던 날은 행이 나오지 않는다.""")
    @GetMapping("/expiry-loss")
    public Response<ExpiryLossResponse> expiryLoss(@RequestParam(defaultValue = "7") int days) {
        return Response.success(analysisService.expiryLoss(days));
    }
}
