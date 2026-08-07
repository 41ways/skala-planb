package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.entity.Ledger;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.LedgerReason;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 원장 한 줄 응답.
 *
 * signedAmount를 같이 내려주는 이유: 화면에서 "+5,000 / -3,000"으로 보여주려면
 * entryType을 보고 부호를 붙여야 하는데, 그 판단을 클라이언트마다 다시 하게 하면
 * 어디선가 반대로 붙는 실수가 생김. 서버가 한 번만 정하는 게 안전함.
 */
@Schema(name = "원장 응답")
public record LedgerResponse(
        Long id,
        String accountId,
        EntryType entryType,

        @Schema(description = "항상 양수")
        Long amount,

        @Schema(description = "방향까지 반영한 금액. CREDIT은 +, DEBIT은 -")
        Long signedAmount,

        @Schema(description = "이 기록 직후 잔액")
        Long balanceAfter,

        LedgerReason reason,
        String refType,
        Long refId,
        String memo,
        LocalDateTime createdAt
) {

    public static LedgerResponse from(Ledger ledger) {
        return new LedgerResponse(
                ledger.getId(),
                ledger.getAccountId(),
                ledger.getEntryType(),
                ledger.getAmount(),
                ledger.signedAmount(),
                ledger.getBalanceAfter(),
                ledger.getReason(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getMemo(),
                ledger.getCreatedAt());
    }
}
