package com.skala.planbmarket.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.ListingStatus;
import com.skala.planbmarket.domain.enums.NotificationType;
import com.skala.planbmarket.domain.enums.TicketStatus;
import com.skala.planbmarket.repository.DepositRepository;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.ListingRepository;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 소멸 처리 서비스.
 *
 * 이 프로젝트의 정체성이 여기 있음 — 아무도 아무것도 안 해도 시간이 지나면 자산이 사라짐.
 * 교재의 상품은 가만히 두면 그대로지만, 여기서는 가만히 두는 것 자체가 상태 변화임.
 *
 * 만료 시점의 처리는 "그 순간 어디까지 진행됐는가"에 따라 갈림:
 *   OPEN       아무 일도 없었음        → 티켓만 실효
 *   RESERVED   예약금이 걸려 있음      → 전액 환불. 구매자 귀책이 아니라 시간이 다한 것
 *   IN_ESCROW  결제까지 끝났음         → 전액 환불. 못 쓰는 티켓 값을 판매자가 가질 수 없음
 *   COMPLETED  이미 확정됨             → 손대지 않음
 *
 * RESERVED와 IN_ESCROW를 둘 다 환불로 정한 근거: 제한시간 안에 결제했고 확정을 기다리는
 * 중이었다면 구매자는 할 일을 다 한 것임. 반대로 판매자도 티켓을 내놓았을 뿐 잘못한 게 없음.
 * 어느 쪽에도 귀책이 없을 때는 돈이 원래 자리로 돌아가는 게 가장 덜 불합리함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryService {

    /** 아직 살아 있는 티켓 상태. 이미 넘어갔거나 실효된 건 다시 볼 필요 없음 */
    private static final List<TicketStatus> ALIVE =
            List.of(TicketStatus.OWNED, TicketStatus.LISTED);

    /** 만료 시점에 정리가 필요한 판매 상태 */
    private static final List<ListingStatus> ACTIVE_LISTING =
            List.of(ListingStatus.OPEN, ListingStatus.RESERVED, ListingStatus.IN_ESCROW);

    private final TicketRepository ticketRepository;
    private final ListingRepository listingRepository;
    private final EscrowRepository escrowRepository;
    private final DepositRepository depositRepository;
    private final EscrowService escrowService;
    private final DepositService depositService;
    private final NotificationService notificationService;

    /**
     * 만료된 티켓을 실효 처리.
     *
     * 티켓을 기준으로 도는 이유: 판매 등록이 안 된 티켓(OWNED)도 만료되면 실효돼야 함.
     * 판매 건 기준으로 돌면 그런 티켓이 영영 안 걸림.
     */
    @Transactional
    public int expireTickets(LocalDateTime now) {
        List<Ticket> expired = ticketRepository.findByStatusInAndExpiresAtBefore(ALIVE, now);

        for (Ticket ticket : expired) {
            Optional<Listing> active = listingRepository
                    .findFirstByTicketIdAndStatusInOrderByIdDesc(ticket.getId(), ACTIVE_LISTING);
            active.ifPresent(listing -> settleExpiredListing(listing, now));

            ticket.changeStatus(TicketStatus.EXPIRED);
            notificationService.notify(ticket.getOwner(), NotificationType.TICKET_EXPIRED,
                    "티켓이 만료되었습니다",
                    "'" + ticket.getTitle() + "' 이(가) 만료되어 더 이상 거래할 수 없습니다.",
                    "TICKET", ticket.getId());
        }

        if (!expired.isEmpty()) {
            log.info("만료 티켓 {}건 실효 처리", expired.size());
        }
        return expired.size();
    }

    /** 만료 시점의 진행 단계에 따라 돈을 되돌림 */
    private void settleExpiredListing(Listing listing, LocalDateTime now) {
        switch (listing.getStatus()) {
            case RESERVED -> depositRepository
                    .findByListingIdAndStatus(listing.getId(), DepositStatus.HELD)
                    .ifPresent(deposit -> {
                        depositService.release(deposit, now, "티켓 만료로 무산 — 예약금 전액 환불");
                        notifyRefund(deposit, listing);
                    });

            case IN_ESCROW -> escrowRepository
                    .findFirstByListingIdAndStatus(listing.getId(), EscrowStatus.HOLDING)
                    .ifPresent(escrow -> {
                        escrowService.voidEscrow(escrow, EscrowStatus.VOIDED, "티켓 만료로 거래 무산");
                        notificationService.notify(escrow.getBuyer(), NotificationType.TICKET_EXPIRED,
                                "거래가 무산되어 전액 환불되었습니다",
                                "'" + listing.getTicket().getTitle() + "' 이(가) 만료되어 결제금 "
                                        + escrow.getAmount() + "원을 돌려드렸습니다.",
                                "ESCROW", escrow.getId());
                    });

            default -> {
                // OPEN — 아무도 손대지 않은 상태라 되돌릴 돈이 없음
            }
        }
        listing.changeStatus(ListingStatus.EXPIRED);
    }

    private void notifyRefund(Deposit deposit, Listing listing) {
        notificationService.notify(deposit.getMember(), NotificationType.TICKET_EXPIRED,
                "예약이 무산되어 예약금을 돌려드렸습니다",
                "'" + listing.getTicket().getTitle() + "' 이(가) 만료되어 예약금 "
                        + deposit.getAmount() + "원을 전액 환불했습니다.",
                "DEPOSIT", deposit.getId());
    }

    /**
     * 만료 임박 경고. 24시간 안에 사라지는 티켓에 대해 한 번만 보냄.
     *
     * 보유자와 예약자 양쪽에 보냄. 보유자에게는 "못 팔면 소멸한다"는 뜻이고
     * 예약자에게는 "내가 잡아둔 게 곧 사라진다"는 뜻이라, 둘 다 지금 움직여야 손해를 면함.
     *
     * expiryWarnedAt으로 중복을 막음. 스케줄러가 1분마다 도는데 이게 없으면
     * 만료 24시간 전부터 1440번 알림이 쌓임.
     */
    @Transactional
    public int warnExpiringTickets(LocalDateTime now, long withinHours) {
        List<Ticket> soon = ticketRepository.findByStatusInAndExpiryWarnedAtIsNullAndExpiresAtBetween(
                ALIVE, now, now.plusHours(withinHours));

        for (Ticket ticket : soon) {
            long hoursLeft = Duration.between(now, ticket.getExpiresAt()).toHours();
            String message = "'" + ticket.getTitle() + "' 이(가) 약 " + hoursLeft + "시간 뒤 만료됩니다.";

            notificationService.notify(ticket.getOwner(), NotificationType.EXPIRY_WARNING,
                    "보유 티켓 만료 임박", message, "TICKET", ticket.getId());

            listingRepository.findFirstByTicketIdAndStatusInOrderByIdDesc(
                            ticket.getId(), List.of(ListingStatus.RESERVED))
                    .flatMap(listing -> depositRepository.findByListingIdAndStatus(
                            listing.getId(), DepositStatus.HELD))
                    .ifPresent(deposit -> notificationService.notify(
                            deposit.getMember(), NotificationType.EXPIRY_WARNING,
                            "예약한 티켓 만료 임박", message, "DEPOSIT", deposit.getId()));

            ticket.markExpiryWarned(now);
        }

        if (!soon.isEmpty()) {
            log.info("만료 임박 경고 {}건 발송", soon.size());
        }
        return soon.size();
    }

    /**
     * 자동 확정.
     *
     * 구매자가 확정을 안 눌러도 판매자가 영영 돈을 못 받는 일은 없어야 함.
     * 확정 시각을 만료보다 앞당겨 잡아둬서, 만료 처리와 부딪히는 일 없이 여기서 먼저 끝남.
     */
    @Transactional
    public int autoConfirmDue(LocalDateTime now) {
        List<Escrow> due = escrowRepository.findByStatusAndAutoConfirmAtBefore(
                EscrowStatus.HOLDING, now);

        for (Escrow escrow : due) {
            escrowService.settle(escrow, now);
            notificationService.notify(escrow.getBuyer(), NotificationType.ESCROW_CONFIRMED,
                    "거래가 자동 확정되었습니다",
                    "'" + escrow.getListing().getTicket().getTitle()
                            + "' 거래가 확정 시각에 도달해 자동으로 확정되었습니다.",
                    "ESCROW", escrow.getId());
        }

        if (!due.isEmpty()) {
            log.info("자동 확정 {}건 처리", due.size());
        }
        return due.size();
    }
}
