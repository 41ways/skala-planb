package com.skala.planbmarket.mapper;

import com.skala.planbmarket.domain.enums.Category;

import lombok.Getter;
import lombok.Setter;

/**
 * 카테고리별 거래 현황 한 줄.
 *
 * 티켓 · 판매 등록 · 거래 세 테이블을 조인해서 카테고리마다 한 줄로 접은 결과임.
 * 실효율 계산은 여기서 안 함 — 0으로 나누는 경우를 SQL에서 다루면 지저분해지고,
 * 어차피 분모를 무엇으로 볼 것인가는 정책이라 자바 쪽에 두는 게 맞음.
 */
@Getter
@Setter
public class CategoryStatRow {

    /** MyBatis가 문자열 컬럼을 enum으로 알아서 바꿔줌 */
    private Category category;

    /** 해당 카테고리 티켓 총 건수 */
    private long ticketCount;

    /** 양도 완료된 건수 */
    private long tradedCount;

    /** 실효된 건수 */
    private long expiredCount;

    /** 확정된 거래 금액 합 */
    private long tradedAmount;

    /** 실효된 티켓의 정가 합 — "버려진 가치" */
    private long lostAmount;

    /** 거래가/정가 비율 평균. 거래가 한 건도 없으면 NULL */
    private Double avgRatio;
}
