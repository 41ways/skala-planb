package com.skala.planbmarket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Member;

/**
 * Member JPA Repository.
 *
 * 지금은 기본 CRUD만. 조회 메서드는 그 기능을 만드는 단계에서 필요한 것만 추가함
 * (비관적 락 메서드는 동시성 단계에서). 안 쓰는 메서드를 미리 깔아두지 않는 게 원칙.
 */
@Repository
public interface MemberRepository extends JpaRepository<Member, String> {
}
