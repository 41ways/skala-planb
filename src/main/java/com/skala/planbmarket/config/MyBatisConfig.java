package com.skala.planbmarket.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 설정.
 *
 * 매퍼 인터페이스에 @Mapper를 붙여도 되지만, 스캔 대상을 한 곳에 적어두면
 * "MyBatis는 이 패키지에만 산다"는 경계가 눈에 보임. JPA Repository와 뒤섞이지 않게
 * 하려는 것 — 어느 조회를 어느 도구로 했는지가 패키지만 봐도 드러나야 함.
 *
 * <p>XML 위치(classpath:mapper/*.xml)와 언더스코어 → 카멜 변환은 application.yml에 있음.
 */
@Configuration
@MapperScan("com.skala.planbmarket.mapper")
public class MyBatisConfig {
}
