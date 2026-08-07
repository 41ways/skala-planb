package com.skala.planbmarket.dto.response;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.entity.Notification;
import com.skala.planbmarket.domain.enums.NotificationType;

import io.swagger.v3.oas.annotations.media.Schema;

/** 알림 응답. */
@Schema(name = "알림 응답")
public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String message,

        @Schema(description = "\"LISTING\" / \"ESCROW\" / \"DEPOSIT\" / \"TICKET\"")
        String refType,

        Long refId,
        Boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getRefType(),
                notification.getRefId(),
                notification.getIsRead(),
                notification.getCreatedAt());
    }
}
