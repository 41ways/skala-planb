package com.skala.planbmarket.service;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.SessionHandler;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.entity.Notification;
import com.skala.planbmarket.domain.enums.NotificationType;
import com.skala.planbmarket.dto.response.NotificationResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 알림 서비스.
 *
 * 이 도메인에선 알림이 부가기능이 아님. 사용자가 가만히 있으면 예약금이 몰수되고
 * 티켓이 소멸함. "지금 뭘 해야 하는지" 알려주는 게 서비스의 일부임.
 *
 * 알림 생성은 부르는 쪽 트랜잭션에 그대로 참여함. 거래가 롤백됐는데 알림만 남으면
 * 사용자가 일어나지도 않은 일을 통보받게 됨.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SessionHandler sessionHandler;

    @Transactional
    public void notify(Member member, NotificationType type, String title, String message,
                       String refType, Long refId) {
        notificationRepository.save(Notification.builder()
                .member(member)
                .type(type)
                .title(title)
                .message(message)
                .refType(refType)
                .refId(refId)
                .isRead(false)
                .build());
    }

    public PagedList<NotificationResponse> list(String memberId, boolean unreadOnly,
                                                int offset, int count) {
        sessionHandler.requireSelf(memberId);
        Page<Notification> page = unreadOnly
                ? notificationRepository.findByMemberIdAndIsReadFalseOrderByIdDesc(
                        memberId, Paging.of(offset, count))
                : notificationRepository.findByMemberIdOrderByIdDesc(memberId, Paging.of(offset, count));
        return PagedList.of(page, offset, count, NotificationResponse::from);
    }

    public long unreadCount(String memberId) {
        sessionHandler.requireSelf(memberId);
        return notificationRepository.countByMemberIdAndIsReadFalse(memberId);
    }

    @Transactional
    public NotificationResponse markRead(Long id) {
        String memberId = sessionHandler.requireLoginMemberId();
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "알림 ID " + id));

        if (!notification.getMember().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 알림이 아닙니다");
        }
        notification.markAsRead();
        return NotificationResponse.from(notification);
    }

    /**
     * 전체 읽음 처리.
     *
     * 한 건씩 불러와 상태를 바꾸는 대신 UPDATE 한 방으로 처리함. 알림은 수백 건까지
     * 쌓일 수 있는데 그걸 전부 영속성 컨텍스트에 올릴 이유가 없음.
     */
    @Transactional
    public int markAllRead(String memberId) {
        sessionHandler.requireSelf(memberId);
        return notificationRepository.markAllReadByMemberId(memberId);
    }
}
