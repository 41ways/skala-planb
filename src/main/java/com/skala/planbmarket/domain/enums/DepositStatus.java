package com.skala.planbmarket.domain.enums;

/**
 * 예약금 상태. HELD에서 시작해 나머지 셋 중 하나로 딱 한 번만 전이됨.
 *
 * 전이될 때마다 원장에 2줄씩 기록이 남음. 예약금은 홀드 시점에 이미 회원 잔액에서
 * 빠져나가서 DEPOSIT_POOL 계정에 들어가 있고, 여기서 어디로 가느냐만 갈림.
 */
public enum DepositStatus {

    /** 홀드 중. 돈은 DEPOSIT_POOL에 잡혀 있음 */
    HELD,

    /** 본결제에 충당됨 → ESCROW_POOL로 이동 */
    CAPTURED,

    /** 몰수 → PLATFORM으로 이동 */
    FORFEITED,

    /** 환불 → 회원에게 되돌아감 */
    RELEASED
}
