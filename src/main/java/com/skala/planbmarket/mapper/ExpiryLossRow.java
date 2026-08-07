package com.skala.planbmarket.mapper;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * 일별 실효 손실 한 줄.
 *
 * 손실을 두 가지로 내는 이유 — 정가와 시장가는 다른 질문에 답함.
 * 정가는 "얼마짜리가 버려졌나", 시장가는 "실제로 얼마에 팔릴 수 있었나".
 * 판매 등록조차 안 하고 썩힌 티켓은 시장가가 0이라, 두 숫자의 차이가
 * "시장에 나오지도 못한 손실"을 드러냄.
 */
@Getter
@Setter
public class ExpiryLossRow {

    /** 실효된 날짜. 티켓의 만료 시각 기준 */
    private LocalDate lossDate;

    /** 그날 실효된 티켓 건수 */
    private long expiredCount;

    /** 그중 판매 등록까지 갔던 건수 */
    private long listedCount;

    /** 정가 기준 손실액 */
    private long originalLoss;

    /** 시장가(희망가) 기준 손실액. 등록 안 한 건은 0으로 빠짐 */
    private long marketLoss;
}
