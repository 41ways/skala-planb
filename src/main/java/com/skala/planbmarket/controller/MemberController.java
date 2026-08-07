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
import com.skala.planbmarket.dto.request.MemberRequests;
import com.skala.planbmarket.dto.response.EscrowResponse;
import com.skala.planbmarket.dto.response.LedgerResponse;
import com.skala.planbmarket.dto.response.MemberResponse;
import com.skala.planbmarket.dto.response.ReservationResponse;
import com.skala.planbmarket.service.EscrowService;
import com.skala.planbmarket.service.MemberService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 회원 가입·로그인·조회. */
@Tag(name = "1. 회원", description = "가입, 로그인, 조회")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final EscrowService escrowService;

    @Operation(summary = "회원 가입")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<MemberResponse.Summary> create(@Valid @RequestBody MemberRequests.Create request) {
        return Response.created(memberService.create(request));
    }

    @Operation(summary = "로그인", description = "성공하면 세션이 잡히고 이후 요청에 자동으로 실려 감")
    @PostMapping("/login")
    public Response<MemberResponse.Summary> login(@Valid @RequestBody MemberRequests.Login request) {
        return Response.success(memberService.login(request));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public Response<Void> logout() {
        memberService.logout();
        return Response.success();
    }

    @Operation(summary = "회원 목록")
    @GetMapping("/list")
    public Response<PagedList<MemberResponse.Summary>> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count) {
        return Response.success(memberService.list(offset, count));
    }

    @Operation(summary = "회원 상세", description = "보유 티켓 포함")
    @GetMapping("/{id}")
    public Response<MemberResponse.Detail> get(@PathVariable String id) {
        return Response.success(memberService.get(id));
    }

    @Operation(summary = "예치금 충전",
            description = "본인만 가능. 실제 결제는 없고 EXTERNAL 계정에서 들어온 것으로 원장에 기록됨")
    @PostMapping("/{id}/charge")
    public Response<MemberResponse.Summary> charge(@PathVariable String id,
                                                   @Valid @RequestBody MemberRequests.Charge request) {
        return Response.success(memberService.charge(id, request));
    }

    @Operation(summary = "내 원장 조회", description = "본인만 가능. 최근 기록부터")
    @GetMapping("/{id}/ledger")
    public Response<PagedList<LedgerResponse>> ledger(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count) {
        return Response.success(memberService.ledger(id, offset, count));
    }

    @Operation(summary = "내 거래 목록", description = "본인만 가능")
    @GetMapping("/{id}/escrows")
    public Response<PagedList<EscrowResponse>> escrows(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count) {
        return Response.success(escrowService.listByMember(id, offset, count));
    }

    @Operation(summary = "내 예약 목록", description = "본인만 가능")
    @GetMapping("/{id}/reservations")
    public Response<PagedList<ReservationResponse>> reservations(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_COUNT) int count) {
        return Response.success(escrowService.listReservations(id, offset, count));
    }

    @Operation(summary = "회원 정보 수정", description = "본인만 가능")
    @PutMapping
    public Response<MemberResponse.Summary> update(@Valid @RequestBody MemberRequests.Update request) {
        return Response.success(memberService.update(request));
    }

    @Operation(summary = "회원 삭제",
            description = "본인만 가능. 원장에 거래 기록이 남아 있으면 409로 거부됨")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable String id) {
        memberService.delete(id);
        return Response.success();
    }
}
