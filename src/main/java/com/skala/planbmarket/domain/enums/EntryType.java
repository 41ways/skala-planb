package com.skala.planbmarket.domain.enums;

/**
 * 원장 기입 방향. 금액은 항상 양수로 넣고 방향만 이걸로 구분함.
 *
 * 금액에 음수를 허용하면 "-5000 DEBIT"이 뭘 뜻하는지 애매해지고 SUM 검증도 꼬임.
 */
public enum EntryType {

    /** 차감 — 이 계정에서 돈이 나감 */
    DEBIT,

    /** 증가 — 이 계정으로 돈이 들어옴 */
    CREDIT
}
