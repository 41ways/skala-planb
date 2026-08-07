package com.skala.planbmarket.common;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 거래 정책 숫자를 모아둔 곳.
 *
 * 이런 값들이 서비스 곳곳에 흩어져 있으면 "왜 하필 10분인가"를 나중에 아무도 설명 못 함.
 * 한 곳에 모아두고 근거를 옆에 적어두면 정책을 바꿀 때 여기만 보면 되고,
 * 보고서에 판단 근거를 옮겨 적기도 쉬움.
 */
public final class TradePolicy {

    private TradePolicy() {
    }

    /**
     * 중개 수수료율(%). 정산할 때 판매자 몫에서 먼저 뗌.
     *
     * 수수료가 0이면 플랫폼 수익이 예약금 몰수밖에 안 남는데, 그러면 플랫폼이
     * 이용자 이탈을 반길 유인이 생겨서 구조가 뒤틀림. 거래가 성사돼야 돈을 버는 게 맞음.
     */
    public static final int COMMISSION_PERCENT = 5;

    /**
     * 예약금 비율(%). 희망가(askingPrice) 기준.
     *
     * 예약을 걸면 판매자는 그동안 다른 구매자를 못 받음. 소멸성 자산이라 그 시간이 곧
     * 손해인데, 아무 대가 없이 판매자에게만 지우면 불공평함. 그래서 이탈에 비용을 붙였음.
     * 너무 높으면 예약 자체를 안 하게 되고 너무 낮으면 붙잡는 효과가 없어서 10%로 잡음.
     */
    public static final int DEPOSIT_PERCENT = 10;

    /**
     * 청약철회 시간(분).
     *
     * 예약 취소와 에스크로 환불에 같은 값을 씀. 규칙이 하나면 설명도 하나로 끝남.
     * 이 시간이 지나면 못 물림 — 소멸성 자산이라 묶여 있는 동안에도 가치가 줄어서,
     * 무기한 환불을 열어두면 판매자만 시간을 날림.
     */
    public static final long COOLING_OFF_MINUTES = 10;

    /** 결제 제한시간 상한(분). 실제로는 남은 시간의 절반과 비교해 더 짧은 쪽을 씀 */
    public static final long PAYMENT_DEADLINE_MAX_MINUTES = 30;

    /** 자동 확정까지 기본 대기 시간 */
    public static final long AUTO_CONFIRM_HOURS = 24;

    /**
     * 자동 확정을 만료보다 이만큼 앞당기는 여유(분).
     *
     * 이게 없으면 자동확정 시각과 만료 시각이 같아져서 두 스케줄러가 같은 건을 놓고 만남.
     * 만료 스케줄러가 1분 주기로 더 자주 도니까 사실상 항상 만료가 먼저 잡히고,
     * 그러면 자동확정이 영영 안 일어남 — 판매자는 티켓을 넘겼는데 돈을 못 받고 끝남.
     * 앞당겨 두면 충돌 자체가 안 생겨서 "어느 쪽이 우선인가"라는 규칙이 필요 없어짐.
     */
    public static final long AUTO_CONFIRM_MARGIN_MINUTES = 10;

    /** 예약금 계산 */
    public static long depositOf(long askingPrice) {
        return askingPrice * DEPOSIT_PERCENT / 100;
    }

    /** 수수료 계산. 원 단위 아래는 버려서 플랫폼이 더 가져가지 않게 함 */
    public static long commissionOf(long sellerAmount) {
        return sellerAmount * COMMISSION_PERCENT / 100;
    }

    /**
     * 결제 제한시간 산출 — now + min(30분, 남은시간 × 0.5).
     *
     * 만료가 임박할수록 결제 시간도 같이 짧아짐. 하루 남은 티켓을 30분 묶어두는 건
     * 괜찮지만 두 시간 남은 티켓에 30분이면 남은 가치의 4분의 1을 묶는 셈이라 과함.
     * 시간이 모든 걸 지배한다는 이 도메인의 성질을 규칙으로 옮긴 것.
     *
     * 계산 결과가 0 이하로 나올 수 있어서(만료 직전 예약) 최소 1분은 보장함.
     */
    public static LocalDateTime paymentDeadlineOf(LocalDateTime now, LocalDateTime expiresAt) {
        long remaining = Duration.between(now, expiresAt).toMinutes();
        long granted = Math.min(PAYMENT_DEADLINE_MAX_MINUTES, remaining / 2);
        return now.plusMinutes(Math.max(1, granted));
    }

    /**
     * 자동 확정 시각 산출 — min(결제 + 24시간, 만료 - 여유).
     *
     * 만료가 코앞일 때 계산 결과가 과거로 나갈 수 있어서 최소 1분 뒤로 밀어줌.
     * 안 그러면 결제하자마자 다음 스케줄러 주기에 바로 확정돼버림.
     */
    public static LocalDateTime autoConfirmAt(LocalDateTime paidAt, LocalDateTime expiresAt) {
        LocalDateTime byDuration = paidAt.plusHours(AUTO_CONFIRM_HOURS);
        LocalDateTime byExpiry = expiresAt.minusMinutes(AUTO_CONFIRM_MARGIN_MINUTES);
        LocalDateTime chosen = byDuration.isBefore(byExpiry) ? byDuration : byExpiry;

        LocalDateTime floor = paidAt.plusMinutes(1);
        return chosen.isBefore(floor) ? floor : chosen;
    }

    /** 청약철회 가능 시간 안에 있는지 */
    public static boolean withinCoolingOff(LocalDateTime from, LocalDateTime now) {
        return Duration.between(from, now).toMinutes() < COOLING_OFF_MINUTES;
    }
}
