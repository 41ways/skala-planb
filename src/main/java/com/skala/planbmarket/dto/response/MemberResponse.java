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
}
