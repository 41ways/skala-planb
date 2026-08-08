package com.skala.planbmarket.dto.response;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.ExpiryType;
import com.skala.planbmarket.domain.enums.TicketStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 티켓 응답.
 *
 * remainingMinutes를 서버에서 계산해서 내려주는 이유: 이 도메인에선 "얼마나 남았나"가
 * 핵심 정보인데, 클라이언트가 expiresAt에서 직접 빼면 기기 시계가 틀어져 있을 때
 * 엉뚱한 카운트다운이 나옴. 만료 판정은 서버 시계가 기준이어야 함.
 */
@Schema(name = "티켓 응답")
public record TicketResponse(
        Long id,
        String ownerId,
        Category category,
        String categoryName,
        ExpiryType expiryType,
        String title,
        Long originalPrice,
        Integer quantity,
        LocalDateTime eventAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate extendedUntil,
        LocalDateTime expiresAt,
        TicketStatus status,

        @Schema(description = "만료까지 남은 분. 이미 지났으면 음수")
        long remainingMinutes
) {

    public static TicketResponse from(Ticket ticket) {
        return from(ticket, LocalDateTime.now());
    }

    /** 목록을 만들 때 now를 한 번만 구해서 넘기면 항목마다 기준 시각이 달라지지 않음 */
    public static TicketResponse from(Ticket ticket, LocalDateTime now) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getOwner().getId(),
                ticket.getCategory(),
                ticket.getCategory().getDisplayName(),
                ticket.getExpiryType(),
                ticket.getTitle(),
                ticket.getOriginalPrice(),
                ticket.getQuantity(),
                ticket.getEventAt(),
                ticket.getValidFrom(),
                ticket.getValidUntil(),
                ticket.getExtendedUntil(),
                ticket.getExpiresAt(),
                ticket.getStatus(),
                Duration.between(now, ticket.getExpiresAt()).toMinutes());
    }
}
