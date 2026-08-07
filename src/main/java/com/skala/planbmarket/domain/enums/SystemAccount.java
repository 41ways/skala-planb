package com.skala.planbmarket.domain.enums;

/**
 * 원장의 시스템 계정. 회원이 아닌 쪽 계정들임.
 *
 * 왜 필요하냐면, 원장 전체에서 SUM(DEBIT) == SUM(CREDIT)이 성립하려면 모든 금전 이동에
 * 상대편이 있어야 함. 충전은 회원 CREDIT만 있고 상대가 없으면 그 순간 차대가 깨짐.
 * 홀드된 예약금도 "회원 잔액에서 빠졌다"까지만 있고 어디 있는지가 없으면 마찬가지.
 *
 * 그래서 돈이 잠깐 머무는 자리마다 계정을 하나씩 만들어줬음. 이러면 원장만 봐도
 * 지금 시스템 안의 돈이 어디에 얼마나 있는지가 전부 드러남.
 */
public enum SystemAccount {

    /** 플랫폼 수익 (몰수분 등) */
    PLATFORM,

    /** 시스템 바깥. 예치금 충전이 흘러들어오는 출처 */
    EXTERNAL,

    /** 결제금 보관소. 결제 시점부터 확정·환불 전까지 여기 잡혀 있음 */
    ESCROW_POOL,

    /** 예약금 보관소 */
    DEPOSIT_POOL;

    /**
     * 이 accountId가 시스템 계정인지 판정.
     *
     * 정합성 검증에서 "회원별 잔액 대조"를 할 때 시스템 계정을 걸러내야 해서 필요함.
     * 회원 ID와 시스템 계정 이름이 같은 컬럼에 들어가는 구조라서, 회원가입 때
     * 이 이름들을 못 쓰게 막는 용도로도 쓸 수 있음.
     */
    public static boolean isSystemAccount(String accountId) {
        for (SystemAccount account : values()) {
            if (account.name().equals(accountId)) {
                return true;
            }
        }
        return false;
    }
}
