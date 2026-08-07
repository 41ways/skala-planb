package com.skala.planbmarket.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.skala.planbmarket.domain.enums.Category;

/**
 * 분석·통계 조회 (MyBatis).
 *
 * JPA와의 경계 기준은 <b>"조회가 복잡한가"가 아니라 "여러 행을 집계해서 줄여야 하는가"</b>임.
 * 여기 있는 세 쿼리는 전부 여러 테이블을 조인해 GROUP BY로 행을 접고 CASE WHEN으로
 * 갈래를 나눔. JPA로 하면 엔티티를 전부 메모리에 올려놓고 자바에서 집계해야 하는데,
 * SQL로 보내면 DB가 접은 결과만 받아옴.
 *
 * <p>반대로 회원 거래 요약({@code GET /api/members/{id}/summary})은 여기 없음.
 * 단일 회원 기준 건수·합계라 행을 구간으로 나눌 일이 없고, JPA 파생 쿼리메서드로 끝남.
 * <b>경계에 걸친 사례를 놓고 판단한 것 자체가 설계 결과물</b>이라 일부러 양쪽에 하나씩 남겨둠.
 *
 * <p>구간 경계를 SQL에 CASE WHEN으로 박지 않고 파라미터로 넘기는 이유는
 * {@link com.skala.planbmarket.domain.enums.RemainingBucket} 주석 참조.
 */
@Mapper
public interface AnalysisMapper {

    /**
     * 같은 카테고리 + 같은 잔여시간 구간의 최근 거래 표본.
     *
     * @param minHours 구간 하한 (포함)
     * @param maxHours 구간 상한 (배제)
     * @param since    이 시각 이후에 확정된 거래만
     */
    RatioSampleRow findRatioByCategoryAndBucket(@Param("category") Category category,
                                                @Param("minHours") int minHours,
                                                @Param("maxHours") int maxHours,
                                                @Param("since") LocalDateTime since);

    /** 1차 폴백 — 구간을 무시하고 카테고리 전체 평균 */
    RatioSampleRow findRatioByCategory(@Param("category") Category category,
                                       @Param("since") LocalDateTime since);

    /** 카테고리별 거래 현황. 티켓이 하나도 없는 카테고리는 행 자체가 안 나옴 */
    List<CategoryStatRow> findCategoryStats();

    /** 일별 실효 손실. 실효가 없던 날은 행이 안 나옴 */
    List<ExpiryLossRow> findExpiryLoss(@Param("since") LocalDateTime since);
}
