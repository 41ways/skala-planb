package com.skala.planbmarket.dto.response;

import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.RemainingBucket;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가격 추천 응답.
 *
 * {@code sampleCount}와 {@code basis}를 반드시 같이 내보냄. 추천가만 주면 사용자는 그 숫자가
 * 100건의 평균인지 1건짜리 우연인지 알 수 없음. 소멸성 자산이라 값을 잘못 매기면
 * 되돌릴 시간이 없어서, "이 값을 얼마나 믿어야 하는가"까지 알려줘야 추천이 성립함.
 *
 * <p>신뢰도 등급(HIGH/MEDIUM/LOW)은 이번 범위 밖 — 표본 몇 건부터 믿을 만한가를 정할
 * 근거가 없어서, 등급으로 뭉개는 대신 원자료(건수·폴백 단계)를 그대로 노출하는 쪽을 택함.
 */
@Schema(name = "가격 추천 응답")
public record PriceSuggestionResponse(

        Long ticketId,
        String title,
        Category category,

        @Schema(description = "정가 (전체 수량 기준)")
        Long originalPrice,

        @Schema(description = "남은 시간(시간 단위). 이미 만료됐으면 음수")
        long hoursLeft,

        @Schema(description = "잔여시간 구간")
        RemainingBucket bucket,

        String bucketLabel,

        @Schema(description = "추천가 산출에 쓰인 표본 건수. 0이면 기본값으로 떨어진 것")
        long sampleCount,

        @Schema(description = "표본의 평균 거래가율 (거래가 / 정가)")
        double avgRatio,

        @Schema(description = "추천가 = avgRatio × originalPrice, 원 단위 반올림")
        long suggestedPrice,

        @Schema(description = "어느 단계에서 값을 구했는지")
        Basis basis,

        String description
) {

    @Schema(name = "가격 추천 산출 근거")
    public enum Basis {

        /** 같은 카테고리 + 같은 잔여시간 구간의 실거래 */
        CATEGORY_BUCKET("같은 카테고리·같은 잔여시간 구간의 최근 거래"),

        /** 구간 표본이 없어 카테고리 전체 평균으로 내려옴 */
        CATEGORY("구간 표본이 없어 카테고리 전체 평균으로 대체"),

        /** 카테고리 표본도 없어 기본값 */
        DEFAULT("표본이 없어 정가의 70%를 기본값으로 적용");

        private final String description;

        Basis(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
