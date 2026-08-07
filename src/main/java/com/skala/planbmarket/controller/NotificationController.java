package com.skala.planbmarket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.dto.response.NotificationResponse;
import com.skala.planbmarket.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** 알림 조회·읽음 처리. 전부 본인 것만 접근 가능. */
@Tag(name = "5. 알림", description = "만료 임박, 결제 마감, 몰수 통보")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록", description = "본인만 가능. 최근 것부터")
    @GetMapping("/members/{id}/notifications")
    public Response<PagedList<NotificationResponse>> list(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count) {
        return Response.success(notificationService.list(id, unreadOnly, offset, count));
    }

    @Operation(summary = "안읽음 개수", description = "본인만 가능")
    @GetMapping("/members/{id}/notifications/unread-count")
    public Response<Long> unreadCount(@PathVariable String id) {
        return Response.success(notificationService.unreadCount(id));
    }

    @Operation(summary = "읽음 처리")
    @PatchMapping("/notifications/{id}/read")
    public Response<NotificationResponse> markRead(@PathVariable Long id) {
        return Response.success(notificationService.markRead(id));
    }

    @Operation(summary = "전체 읽음 처리", description = "처리한 건수를 돌려줌")
    @PatchMapping("/members/{id}/notifications/read-all")
    public Response<Integer> markAllRead(@PathVariable String id) {
        return Response.success(notificationService.markAllRead(id));
    }
}
