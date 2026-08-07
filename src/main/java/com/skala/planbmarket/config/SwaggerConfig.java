package com.skala.planbmarket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger 설정.
 *
 * 태그는 각 컨트롤러의 @Tag로 붙임. 여기서 태그 순서를 정해두는 이유는, 안 그러면
 * 알파벳 순으로 섞여서 "회원 → 티켓 → 판매 → 거래"라는 실제 사용 흐름이 안 보임.
 * 시연할 때 위에서 아래로 순서대로 눌러볼 수 있어야 함.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI planbOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("PlanB Market API")
                .version("1.0.0")
                .description("""
                        소멸성 자산(만료 기한이 있는 티켓)의 P2P 양도 플랫폼.

                        인증은 세션 기반입니다. `POST /api/members/login` 을 먼저 호출하면
                        브라우저 쿠키에 세션이 잡히고, 이후 요청에 자동으로 실려 갑니다.

                        조회 API는 대체로 공개이고, 등록·구매·철회 같은 변경 API는 로그인이 필요합니다.
                        원장·알림·내 거래처럼 개인정보에 해당하는 조회는 본인만 볼 수 있습니다."""));
    }
}
