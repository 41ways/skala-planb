package com.skala.planbmarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PlanB Market — 소멸성 자산(만료 기한이 있는 티켓)의 P2P 양도 플랫폼.
 *
 * 스케줄러·AOP 활성화는 해당 기능을 만드는 단계에서 붙임.
 */
@SpringBootApplication
public class PlanbMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanbMarketApplication.class, args);
    }
}
