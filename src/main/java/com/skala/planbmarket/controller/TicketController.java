package com.skala.planbmarket.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.dto.request.TicketRequests;
import com.skala.planbmarket.dto.response.TicketResponse;
import com.skala.planbmarket.service.TicketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 티켓 등록·조회·수정. 판매 등록 전 단계. */
@Tag(name = "2. 티켓", description = "양도 대상 자산 관리")
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "티켓 등록",
            description = "카테고리에 따라 필요한 날짜가 다름. 시점 만료는 eventAt, 기간 만료는 validUntil")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<TicketResponse> create(@Valid @RequestBody TicketRequests.Create request) {
        return Response.created(ticketService.create(request));
    }

    @Operation(summary = "티켓 목록", description = "만료가 가까운 것부터")
    @GetMapping("/list")
    public Response<PagedList<TicketResponse>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count,
            @RequestParam(required = false) Category category) {
        return Response.success(ticketService.list(offset, count, category));
    }

    @Operation(summary = "티켓 상세")
    @GetMapping("/{id}")
    public Response<TicketResponse> get(@PathVariable Long id) {
        return Response.success(ticketService.get(id));
    }

    @Operation(summary = "티켓 수정", description = "판매 등록 전(OWNED) 상태에서만 가능")
    @PutMapping("/{id}")
    public Response<TicketResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody TicketRequests.Update request) {
        return Response.success(ticketService.update(id, request));
    }

    @Operation(summary = "티켓 삭제", description = "판매 등록 전(OWNED) 상태에서만 가능")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return Response.success();
    }

    @Operation(summary = "기한 연장", description = "기프티콘(EXTENDABLE) 전용")
    @PostMapping("/{id}/extend")
    public Response<TicketResponse> extend(@PathVariable Long id,
                                           @Valid @RequestBody TicketRequests.Extend request) {
        return Response.success(ticketService.extend(id, request));
    }
}
