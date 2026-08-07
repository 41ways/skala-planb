package com.skala.planbmarket.mapper;

import lombok.Getter;
import lombok.Setter;

/**
 * 가격 추천 표본 집계 결과 — "몇 건이 있었고 평균 몇 %에 팔렸나".
 *
 * 응답 DTO(record)로 바로 받지 않고 이 클래스를 거치는 이유: 추천가는 SQL이 아니라
 * 서비스가 정함(폴백 단계를 거치며 여러 번 조회할 수 있고, 정가를 곱하는 것도 여기 아님).
 * SQL은 "표본이 이렇더라"까지만 답하고 판단은 자바가 하게 나눠둔 것.
 *
 * <p>MyBatis가 setter로 채우므로 record가 아니라 일반 클래스임.
 */
@Getter
@Setter
public class RatioSampleRow {

    /** 표본 건수. 0이면 폴백 단계로 내려가야 함 */
    private long sampleCount;

    /**
     * 거래가 / 정가 비율의 평균.
     *
     * 표본이 0건이면 SQL의 AVG가 NULL을 주므로 Double(래퍼)로 받음.
     * primitive로 받으면 NPE가 나는데, 하필 폴백을 타야 할 바로 그 상황에서 터짐.
     */
    private Double avgRatio;
}
