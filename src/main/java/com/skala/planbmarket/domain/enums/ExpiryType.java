package com.skala.planbmarket.domain.enums;

/**
 * 만료 처리 방식.
 *
 * 카테고리가 달라도 만료 판정이 같으면 코드는 하나면 되니까 이렇게 뽑아냈음.
 * 카테고리(무엇을 파는가)와 만료 유형(어떻게 소멸하는가)은 서로 다른 분류 축이라서
 * Category enum이 이 값을 필드로 들고 있는 구조로 감.
 */
public enum ExpiryType {

    /** 공연·상영·출발 시각이 지나면 끝. eventAt이 곧 만료 시각 */
    POINT_IN_TIME,

    /** 유효기간 마지막 날 자정에 끝. validUntil 23:59:59 */
    DATE_RANGE,

    /** 기프티콘처럼 기한을 미룰 수 있음. extendedUntil이 있으면 그걸 우선 */
    EXTENDABLE
}
