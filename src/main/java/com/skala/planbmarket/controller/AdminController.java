package com.skala.planbmarket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.skala.planbmarket.common.Response;
import com.skala.planbmarket.dto.response.IntegrityCheckResponse;
import com.skala.planbmarket.service.AdminService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 관리·검증 API.
 *
 * 로그인을 안 걸어둠. 시연할 때 아무 때나 눌러서 상태를 확인할 수 있어야 하고,
 * 개인 잔액이 아니라 시스템 전체가 맞는지만 보여주는 거라 노출해도 문제없음.
 * 실제 서비스라면 관리자 권한을 걸어야 하는 자리라는 건 보고서에 적어둘 것.
 */
@Tag(name = "5. 관리·검증", description = "정합성 자가검증")
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
}
