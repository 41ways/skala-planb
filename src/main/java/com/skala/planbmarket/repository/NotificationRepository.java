package com.skala.planbmarket.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Notification;

/**
 * Notification JPA Repository.
 *
 * 알림은 회원 기준으로만 조회함. 다른 사람 알림을 볼 일이 없어서 memberId가 안 들어간
 * 조회 메서드는 아예 만들지 않았음 — 실수로 남의 알림을 긁어오는 코드가 나올 여지를 줄임.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberIdOrderByIdDesc(String memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndIsReadFalseOrderByIdDesc(String memberId, Pageable pageable);

    long countByMemberIdAndIsReadFalse(String memberId);

    /**
     * 전체 읽음 처리.
     *
     * clearAutomatically를 켠 이유: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않아서,
     * 이미 메모리에 올라와 있던 알림 객체는 여전히 안 읽음 상태로 남아 있음.
     * 비워주지 않으면 같은 트랜잭션에서 조회할 때 옛날 값이 나옴.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = TRUE WHERE n.member.id = :memberId AND n.isRead = FALSE")
    int markAllReadByMemberId(@Param("memberId") String memberId);
}
