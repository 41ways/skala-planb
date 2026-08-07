package com.skala.planbmarket.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.dto.request.ListingRequests;
import com.skala.planbmarket.dto.response.ListingResponse;
import com.skala.planbmarket.service.ListingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 판매 등록·조회·철회. */
@Tag(name = "3. 판매 등록", description = "티켓을 시장에 내놓기")
@RestController
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @Operation(summary = "판매 등록",
            description = "희망가는 전체 금액. 2매 티켓이면 수량으로 나눠떨어져야 함")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ListingResponse> create(@Valid @RequestBody ListingRequests.Create request) {
        return Response.created(listingService.create(request));
    }

    @Operation(summary = "판매 목록", description = "sort: expiry(기본) / price / recent")
    @GetMapping("/list")
    public Response<PagedList<ListingResponse>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) String sort) {
        return Response.success(listingService.list(offset, count, category, sort));
    }

    @Operation(summary = "만료 임박 목록", description = "지정한 시간 안에 소멸하는 판매 건")
    @GetMapping("/expiring-soon")
    public Response<List<ListingResponse>> expiringSoon(@RequestParam(defaultValue = "24") int hours) {
        return Response.success(listingService.expiringSoon(hours));
    }

    @Operation(summary = "판매 상세")
    @GetMapping("/{id}")
    public Response<ListingResponse> get(@PathVariable Long id) {
        return Response.success(listingService.get(id));
    }

    @Operation(summary = "판매자 철회",
            description = "결제까지 끝난 거래가 붙어 있으면 409로 거부됨")
    @DeleteMapping("/{id}")
    public Response<Void> withdraw(@PathVariable Long id) {
        listingService.withdraw(id);
        return Response.success();
    }
}
