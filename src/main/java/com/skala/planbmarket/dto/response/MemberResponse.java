package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.skala.planbmarket.domain.entity.Member;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원 응답.
 *
 * 비밀번호는 어떤 경우에도 안 나감. 평문 저장이라 더더욱.
 * 요약(Summary)과 상세(Detail)를 나눈 건 목록 조회에서 회원마다 티켓을 다 끌고 오면
 * N+1이 나기 때문임. 목록은 Summary, 단건 조회만 Detail.
 */
public final class MemberResponse {

    private MemberResponse() {
    }

    @Schema(name = "회원 요약 응답")
    public record Summary(
            String id,
            Long balance,
            LocalDateTime createdAt
    ) {
        public static Summary from(Member member) {
            return new Summary(member.getId(), member.getBalance(), member.getCreatedAt());
        }
    }

    @Schema(name = "회원 상세 응답", description = "보유 티켓 포함")
    public record Detail(
            String id,
            Long balance,
            LocalDateTime createdAt,
            List<TicketResponse> tickets
    ) {
        public static Detail of(Member member, List<TicketResponse> tickets) {
            return new Detail(member.getId(), member.getBalance(), member.getCreatedAt(), tickets);
        }
    }

    /**
     * 거래 요약.
     *
     * 전부 단일 회원 기준 건수·합계라 JPA로 만듦 — 여러 행을 구간으로 접는 작업이 아님.
     * 같은 "통계"라도 카테고리별 현황(MyBatis)과는 성격이 다르다는 걸 보여주는 자리라
     * 일부러 양쪽에 하나씩 남겨뒀음.
     */
    @Schema(name = "회원 거래 요약 응답", description = "보유·판매·구매 현황 (JPA 집계)")
    public record TradeSummary(

            String memberId,
            Long balance,

            @Schema(description = "보유 중이고 아직 판매 등록 안 한 티켓")
            long ownedTickets,

            @Schema(description = "판매 등록된 티켓")
            long listedTickets,

            @Schema(description = "못 쓰고 소멸시킨 티켓")
            long expiredTickets,

            @Schema(description = "진행 중인 판매 건 (OPEN·RESERVED·IN_ESCROW)")
            long activeListings,

            @Schema(description = "판매자로서 성사시킨 거래 수")
            long completedSales,

            @Schema(description = "구매자로서 확정한 거래 수")
            long completedPurchases,

            @Schema(description = "진행 중인 내 예약 (예약금 홀드 상태)")
            long activeReservations,

            @Schema(description = "구매 확정 총액")
            long totalPurchased,

            @Schema(description = "판매 정산 수령 총액 (중개 수수료 뗀 실수령)")
            long totalEarned
    ) {
    }
}
