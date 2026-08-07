package com.skala.planbmarket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.dto.request.AdminRequests;
import com.skala.planbmarket.dto.response.ConcurrencyTestResponse;
import com.skala.planbmarket.dto.response.IntegrityCheckResponse;
import com.skala.planbmarket.service.AdminService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리·검증 API.
 *
 * 로그인을 안 걸어둠. 시연할 때 아무 때나 눌러서 상태를 확인할 수 있어야 하고,
 * 개인 잔액이 아니라 시스템 전체가 맞는지만 보여주는 거라 노출해도 문제없음.
 * 실제 서비스라면 관리자 권한을 걸어야 하는 자리라는 건 보고서에 적어둘 것.
 */
@Tag(name = "7. 관리·검증", description = "정합성 자가검증, 동시성 테스트")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "정합성 자가검증",
            description = """
                    세 가지를 서로 다른 각도에서 확인함.
                    1) 회원마다 잔액과 원장 합이 같은가
                    2) 전체 차변과 대변이 같은가 (돈이 새거나 생겨나지 않았는가)
                    3) ESCROW_POOL 잔액이 보관 중인 거래 금액 합과 같은가

                    passed가 false면 어딘가에 버그가 있다는 뜻임.""")
    @GetMapping("/integrity-check")
    public Response<IntegrityCheckResponse> integrityCheck() {
        return Response.success(adminService.integrityCheck());
    }

    @Operation(summary = "동시성 테스트",
            description = """
                    같은 판매 건에 N개 스레드가 동시에 예약을 건다.
                    `useLock`만 바꿔서 두 번 돌리고 응답을 나란히 놓으면 락의 효과가 그대로 보인다.

                    - `useLock: false` → 여러 요청이 같은 OPEN을 읽어 예약이 여러 건 생김. `dataIntegrity: false`
                    - `useLock: true`  → 1건만 성공, 나머지는 기다렸다가 ALREADY_RESERVED

                    바뀌는 건 조회에 락을 거느냐 뿐이고 나머지 코드 경로는 완전히 같다.

                    **응답의 `ledgerBalanced`를 같이 볼 것.** 중복 예약이 생겨도 원장은 맞는다 —
                    홀드마다 2줄이 제대로 남기 때문. 정합성 검증이 못 잡는 종류의 버그라는 뜻이고,
                    돈이 맞는 것과 도메인 규칙이 지켜지는 것이 다른 문제라는 근거다.

                    판매 건은 OPEN이어야 하며, 끝나면 만들어진 예약을 전액 환불로 되돌린다
                    (안 그러면 시연을 한 번밖에 못 함).""")
    @PostMapping("/simulate-concurrent")
    public Response<ConcurrencyTestResponse> simulateConcurrent(
            @Valid @RequestBody AdminRequests.SimulateConcurrent request) {
        return Response.success(adminService.simulateConcurrent(request));
    }
}
