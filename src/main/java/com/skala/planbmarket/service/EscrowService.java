package com.skala.planbmarket.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.SessionHandler;
import com.skala.planbmarket.common.TradePolicy;
import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.ListingStatus;
import com.skala.planbmarket.domain.enums.NotificationType;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.domain.enums.TicketStatus;
import com.skala.planbmarket.dto.response.EscrowResponse;
import com.skala.planbmarket.dto.response.ReservationResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.DepositRepository;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.ListingRepository;
import com.skala.planbmarket.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 구매 흐름 서비스 — 예약 → 결제 → 확정.
 *
 * 한 번에 사지 않고 예약을 거치는 이유는, 소멸성 자산이라 "살까 말까" 고민하는 동안에도
 * 티켓이 계속 소멸하기 때문임. 예약이 걸리면 판매자는 그동안 다른 구매자를 못 받는데,
 * 그 기회비용을 아무 대가 없이 판매자에게만 지우면 불공평함. 그래서 예약에 값을 붙였음.
 *
 * 그리고 결제한 뒤에도 돈이 판매자에게 바로 가지 않고 ESCROW_POOL에 묶임.
 * 티켓을 제대로 받았는지 확인할 시간이 필요한데 그 사이에도 티켓은 만료를 향해 가고,
 * 만료되면 거래 자체가 무의미해지니까 그때는 구매자에게 돌아가야 하기 때문임.
 *
 * 돈은 전부 LedgerService.transfer()로만 움직임. 여기서 잔액을 직접 건드리는 코드는 없음.
 * 비관적 락은 8단계에서 붙임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EscrowService {

    private static final String REF_TYPE = "ESCROW";

    private final EscrowRepository escrowRepository;
    private final ListingRepository listingRepository;
    private final MemberRepository memberRepository;
    private final DepositRepository depositRepository;
    private final DepositService depositService;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    private final SessionHandler sessionHandler;

    // ══════════════════════════════════════════════════════════════
    // 1단계 — 예약
    // ══════════════════════════════════════════════════════════════

    /**
     * 구매 예약. 희망가의 10%를 예약금으로 걸고 자리를 잡음.
     *
     * 잔액은 예약금만 확인함. 본결제 금액까지 보지는 않는데, 제한시간 안에 충전할 수
     * 있기 때문임. 대신 결제를 못 하면 예약금을 몰수당함 — 그 위험을 감수할지는
     * 예약하는 쪽이 정할 일이라고 봤음.
     */
    @Transactional
    public ReservationResponse reserve(Long listingId) {
        return reserve(listingId, sessionHandler.requireLoginMemberId(), true);
    }

    /**
     * 예약 본체. 구매자를 밖에서 받고, 락을 걸지 말지도 밖에서 정한다.
     *
     * <p>인자가 둘 는 이유는 <b>동시성 시뮬레이터 때문</b>이다.
     * <ul>
     *   <li>{@code buyerId} — 시뮬레이터는 여러 스레드에서 부르는데, 세션은 요청 스코프라
     *       그 스레드들에는 없다. 로그인 조회를 위에 남기고 여기선 ID만 받는다.</li>
     *   <li>{@code useLock} — 락이 있고 없고를 <b>같은 코드로</b> 비교하기 위해서다.
     *       메서드를 두 벌 두면 "락 말고 다른 게 달랐던 것 아니냐"는 반론에 답할 수 없다.
     *       바뀌는 건 조회 메서드 두 줄뿐이고 나머지 경로는 완전히 같다.</li>
     * </ul>
     *
     * <p>실제 API({@link #reserve(Long)})는 {@code useLock=true}로 고정이다.
     * 끄는 건 시뮬레이터만 할 수 있고, 그것도 "락이 없으면 이렇게 깨진다"를
     * 보여주려는 용도다.
     *
     * <p><b>락 획득 순서: Listing → 구매자.</b> 판매자는 예약 단계에서 안 건드린다.
     * 확정 단계에서 판매자를 잡을 때도 이 순서 뒤에 오게 되어 있다.
     */
    @Transactional
    public ReservationResponse reserve(Long listingId, String buyerId, boolean useLock) {
        Listing listing = useLock ? findListingForUpdate(listingId) : findListing(listingId);
        Ticket ticket = listing.getTicket();
        LocalDateTime now = LocalDateTime.now();

        if (listing.getSeller().getId().equals(buyerId)) {
            throw new ResponseException(Error.SELF_TRADE_NOT_ALLOWED, "판매 ID " + listingId);
        }
        if (!listing.isOpenForReservation()) {
            throw new ResponseException(reservationBlockedReason(listing),
                    "현재 상태: " + listing.getStatus());
        }
        if (ticket.isExpired(now)) {
            throw new ResponseException(Error.TICKET_EXPIRED, "만료 시각 " + ticket.getExpiresAt());
        }

        Member buyer = findMember(buyerId, useLock);

        long depositAmount = TradePolicy.depositOf(listing.getAskingPrice());
        LocalDateTime deadline = TradePolicy.paymentDeadlineOf(now, ticket.getExpiresAt());

        Deposit deposit = depositService.hold(buyer, listing, depositAmount, deadline);
        listing.changeStatus(ListingStatus.RESERVED);

        return ReservationResponse.from(deposit, TradePolicy.COOLING_OFF_MINUTES);
    }

    /** 예약 현황 조회. 판매 건에 지금 예약이 걸려 있는지 */
    public ReservationResponse reservationOf(Long listingId) {
        Deposit deposit = findActiveReservation(listingId);
        String memberId = sessionHandler.requireLoginMemberId();

        // 예약 당사자와 판매자만 볼 수 있음. 누가 예약했는지는 남에게 알릴 정보가 아님
        if (!deposit.getMember().getId().equals(memberId)
                && !deposit.getListing().getSeller().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "예약 당사자만 조회할 수 있습니다");
        }
        return ReservationResponse.from(deposit, TradePolicy.COOLING_OFF_MINUTES);
    }

    /**
     * 예약 취소.
     *
     * 10분 안이면 청약철회로 보고 전액 돌려줌. 지나면 이탈로 보고 몰수함.
     * 같은 10분 규칙을 에스크로 환불에도 쓰고 있음 — 규칙이 하나면 설명도 하나로 끝남.
     */
    @Transactional
    public ReservationResponse cancelReservation(Long listingId) {
        String memberId = sessionHandler.requireLoginMemberId();
        Deposit deposit = findActiveReservation(listingId);
        LocalDateTime now = LocalDateTime.now();

        if (!deposit.getMember().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 예약이 아닙니다");
        }

        if (TradePolicy.withinCoolingOff(deposit.getHeldAt(), now)) {
            depositService.release(deposit, now, "청약철회 — 예약금 전액 환불");
        } else {
            depositService.forfeit(deposit, now, "예약 취소 — 예약금 몰수");
        }

        deposit.getListing().changeStatus(ListingStatus.OPEN);
        return ReservationResponse.from(deposit, TradePolicy.COOLING_OFF_MINUTES);
    }

    // ══════════════════════════════════════════════════════════════
    // 2단계 — 결제
    // ══════════════════════════════════════════════════════════════

    /**
     * 본결제. 예약금이 결제액에 충당됨.
     *
     * 돈이 두 갈래로 ESCROW_POOL에 모임 — 이미 홀드돼 있던 예약금은 DEPOSIT_POOL에서
     * 넘어오고, 나머지는 구매자 잔액에서 빠져나감. 구매자에게서 희망가 전액을 다시 받으면
     * 예약금을 두 번 받는 셈이 되니 그러면 안 됨.
     */
    @Transactional
    public EscrowResponse pay(Long listingId) {
        String buyerId = sessionHandler.requireLoginMemberId();

        // 예약을 찾기 전에 판매 건부터 잠근다. 예약 조회와 결제 사이에 스케줄러가
        // 시한 초과로 몰수해버리거나 다른 요청이 끼어들 수 있어서.
        // 순서는 예약과 같은 Listing → 구매자. 여기만 반대로 잡으면 데드락이 난다
        Listing listing = findListingForUpdate(listingId);
        Deposit deposit = findActiveReservation(listingId);
        Ticket ticket = listing.getTicket();
        LocalDateTime now = LocalDateTime.now();

        if (!deposit.getMember().getId().equals(buyerId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 예약이 아닙니다");
        }
        if (deposit.isOverdue(now)) {
            // 스케줄러가 아직 안 돌았을 뿐 이미 시한을 넘긴 예약임. 여기서도 막아야
            // 스케줄러 주기(1분) 사이에 슬쩍 결제되는 걸 방지할 수 있음
            throw new ResponseException(Error.PAYMENT_DEADLINE_PASSED,
                    "제한시간 " + deposit.getPaymentDeadline());
        }
        if (ticket.isExpired(now)) {
            throw new ResponseException(Error.TICKET_EXPIRED, "만료 시각 " + ticket.getExpiresAt());
        }

        long total = listing.getAskingPrice();
        long fromDeposit = deposit.getAmount();
        long fromBalance = total - fromDeposit;

        // 구매자 행도 잠근다. 다른 판매 건을 동시에 결제하면 같은 잔액을 두 트랜잭션이
        // 읽고 각자 뺀 값을 써서, 나중 커밋이 앞의 차감을 덮어쓴다
        Member buyer = findMember(buyerId, true);
        if (!buyer.canAfford(fromBalance)) {
            throw new ResponseException(Error.INSUFFICIENT_BALANCE,
                    "잔액 " + buyer.getBalance() + "원, 추가 필요 " + fromBalance + "원");
        }

        Escrow escrow = escrowRepository.save(Escrow.builder()
                .listing(listing)
                .buyer(buyer)
                .quantity(ticket.getQuantity())
                .amount(total)
                .status(EscrowStatus.HOLDING)
                .paidAt(now)
                .autoConfirmAt(TradePolicy.autoConfirmAt(now, ticket.getExpiresAt()))
                .build());

        depositService.capture(deposit, now, escrow.getId());
        if (fromBalance > 0) {
            ledgerService.transfer(buyerId, SystemAccount.ESCROW_POOL.name(), fromBalance,
                    LedgerReason.PURCHASE, REF_TYPE, escrow.getId(), "잔액 결제분");
        }

        listing.changeStatus(ListingStatus.IN_ESCROW);
        return EscrowResponse.from(escrow);
    }

    // ══════════════════════════════════════════════════════════════
    // 3단계 — 확정·환불
    // ══════════════════════════════════════════════════════════════

    /**
     * 구매 확정 → 판매자 정산.
     *
     * 결제 총액에서 수수료만 떼고 판매자에게 넘김. 예약금으로 충당된 몫도 이미
     * ESCROW_POOL에 들어와 있어서 여기선 출처를 구분할 필요가 없음.
     */
    @Transactional
    public EscrowResponse confirm(Long escrowId) {
        String memberId = sessionHandler.requireLoginMemberId();
        Escrow escrow = findEscrow(escrowId);

        if (!escrow.getBuyer().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 거래가 아닙니다");
        }
        settle(escrow, LocalDateTime.now());
        return EscrowResponse.from(escrow);
    }

    /**
     * 정산 실행. 자동확정 스케줄러도 이걸 부르게 될 것.
     *
     * 주의: 같은 클래스 안에서 부르면 스프링 AOP 프록시를 안 거쳐서 @Transactional이
     * 새로 안 걸림. 여기선 부르는 쪽이 이미 트랜잭션 안이라 문제 없지만,
     * 스케줄러에서 쓸 땐 스케줄러 메서드에 트랜잭션이 걸려 있어야 함.
     */
    void settle(Escrow escrow, LocalDateTime now) {
        if (!escrow.isHolding()) {
            throw new ResponseException(Error.LISTING_NOT_OPEN, "현재 상태: " + escrow.getStatus());
        }

        escrow.confirm(now);

        long sellerAmount = escrow.sellerAmount();
        long commission = TradePolicy.commissionOf(sellerAmount);
        String sellerId = escrow.getListing().getSeller().getId();
        String pool = SystemAccount.ESCROW_POOL.name();

        if (commission > 0) {
            ledgerService.transfer(pool, SystemAccount.PLATFORM.name(), commission,
                    LedgerReason.COMMISSION, REF_TYPE, escrow.getId(),
                    "중개 수수료 " + TradePolicy.COMMISSION_PERCENT + "%");
        }
        ledgerService.transfer(pool, sellerId, sellerAmount - commission,
                LedgerReason.SELLER_SETTLE, REF_TYPE, escrow.getId(), "거래 확정 정산");

        Ticket ticket = escrow.getListing().getTicket();
        ticket.transferTo(escrow.getBuyer());
        ticket.changeStatus(TicketStatus.TRANSFERRED);
        escrow.getListing().changeStatus(ListingStatus.COMPLETED);

        // 구매자에겐 안 보냄. 직접 확정을 누른 경우엔 이미 아는 일이고,
        // 자동 확정으로 온 경우는 스케줄러가 따로 알려줌
        notificationService.notify(escrow.getListing().getSeller(),
                NotificationType.ESCROW_CONFIRMED, "거래가 확정되어 정산되었습니다",
                "'" + ticket.getTitle() + "' 거래가 확정되어 "
                        + (sellerAmount - commission) + "원이 입금되었습니다.",
                "ESCROW", escrow.getId());
    }

    /**
     * 환불 요청.
     *
     * 결제 후 10분 안에만 됨. 예약 취소와 같은 규칙임 — 무기한 환불을 열어두면
     * 소멸성 자산이라 판매자만 시간을 날림.
     */
    @Transactional
    public EscrowResponse refund(Long escrowId) {
        String memberId = sessionHandler.requireLoginMemberId();
        Escrow escrow = findEscrow(escrowId);
        LocalDateTime now = LocalDateTime.now();

        if (!escrow.getBuyer().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 거래가 아닙니다");
        }
        if (!escrow.isHolding()) {
            throw new ResponseException(Error.LISTING_NOT_OPEN, "현재 상태: " + escrow.getStatus());
        }
        if (!TradePolicy.withinCoolingOff(escrow.getPaidAt(), now)) {
            throw new ResponseException(Error.PAYMENT_DEADLINE_PASSED,
                    "환불은 결제 후 " + TradePolicy.COOLING_OFF_MINUTES + "분 이내만 가능합니다");
        }

        voidEscrow(escrow, EscrowStatus.REFUNDED, "구매자 환불 요청");
        return EscrowResponse.from(escrow);
    }

    /**
     * 거래를 물리고 돈을 구매자에게 돌려줌. 환불(구매자 요청)과 무산(만료) 둘 다 여기로 옴.
     *
     * 예약금으로 냈던 몫까지 합쳐서 한 번에 돌려줌. 이미 ESCROW_POOL에 섞여 들어와서
     * 출처를 구분할 이유도 없고, 구매자 입장에선 낸 돈 전부가 돌아오는 게 맞음.
     */
    void voidEscrow(Escrow escrow, EscrowStatus finalStatus, String memo) {
        escrow.close(finalStatus);

        ledgerService.transfer(SystemAccount.ESCROW_POOL.name(), escrow.getBuyer().getId(),
                escrow.getAmount(), LedgerReason.ESCROW_REFUND, REF_TYPE, escrow.getId(), memo);

        // 다시 팔 수 있게 되돌림. 티켓은 아직 판매자 소유라 LISTED 그대로
        escrow.getListing().changeStatus(ListingStatus.OPEN);
    }

    // ══════════════════════════════════════════════════════════════
    // 조회
    // ══════════════════════════════════════════════════════════════

    public EscrowResponse get(Long escrowId) {
        Escrow escrow = findEscrow(escrowId);
        String memberId = sessionHandler.requireLoginMemberId();

        if (!escrow.getBuyer().getId().equals(memberId)
                && !escrow.getListing().getSeller().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "거래 당사자만 조회할 수 있습니다");
        }
        return EscrowResponse.from(escrow);
    }

    public PagedList<EscrowResponse> listByMember(String memberId, int offset, int count) {
        sessionHandler.requireSelf(memberId);
        Page<Escrow> page = escrowRepository.findByBuyerIdOrderByIdDesc(memberId, Paging.of(offset, count));
        return PagedList.of(page, offset, count, EscrowResponse::from);
    }

    public PagedList<ReservationResponse> listReservations(String memberId, int offset, int count) {
        sessionHandler.requireSelf(memberId);
        Page<Deposit> page = depositRepository.findByMemberIdOrderByIdDesc(memberId, Paging.of(offset, count));
        return PagedList.of(page, offset, count,
                d -> ReservationResponse.from(d, TradePolicy.COOLING_OFF_MINUTES));
    }

    // ══════════════════════════════════════════════════════════════
    // 스케줄러·다른 서비스가 쓰는 처리
    // ══════════════════════════════════════════════════════════════

    /** 제한시간을 넘긴 예약을 몰수 처리하고 판매 건을 다시 열어줌 */
    @Transactional
    public int expireOverdueReservations(LocalDateTime now) {
        List<Deposit> overdue = depositRepository
                .findByStatusAndPaymentDeadlineBefore(DepositStatus.HELD, now);

        for (Deposit deposit : overdue) {
            depositService.forfeit(deposit, now, "결제 제한시간 초과 — 예약금 몰수");
            Listing listing = deposit.getListing();
            if (listing.getStatus() == ListingStatus.RESERVED) {
                listing.changeStatus(ListingStatus.OPEN);
            }
            notificationService.notify(deposit.getMember(), NotificationType.DEPOSIT_FORFEITED,
                    "결제 시간이 지나 예약이 취소되었습니다",
                    "'" + listing.getTicket().getTitle() + "' 예약금 " + deposit.getAmount()
                            + "원이 몰수되었습니다.",
                    "DEPOSIT", deposit.getId());
        }
        return overdue.size();
    }

    /**
     * 결제 마감 임박 경고. 주어진 시간의 절반을 지난 예약에 한 번만 보냄.
     *
     * 고정분이 아니라 비율로 잡은 이유는 Deposit.needsDeadlineWarning()에 적어뒀음 —
     * 제한시간 자체가 티켓마다 다르기 때문임.
     */
    @Transactional
    public int warnUpcomingDeadlines(LocalDateTime now) {
        int sent = 0;
        for (Deposit deposit : depositRepository.findByStatusAndWarnedAtIsNull(DepositStatus.HELD)) {
            if (!deposit.needsDeadlineWarning(now)) {
                continue;
            }
            long minutesLeft = java.time.Duration.between(now, deposit.getPaymentDeadline()).toMinutes();
            notificationService.notify(deposit.getMember(), NotificationType.PAYMENT_DEADLINE,
                    "결제 마감이 임박했습니다",
                    "'" + deposit.getListing().getTicket().getTitle() + "' 결제까지 약 "
                            + Math.max(0, minutesLeft) + "분 남았습니다. 지나면 예약금이 몰수됩니다.",
                    "DEPOSIT", deposit.getId());
            deposit.markWarned(now);
            sent++;
        }
        return sent;
    }

    /**
     * 판매 건에 걸린 예약을 전부 풀고 다시 열어줌. 동시성 시뮬레이터의 뒷정리용.
     *
     * 락 없이 돌리면 예약이 여러 건 생기는데, 그걸 그대로 두면 판매 건 하나에 HELD가
     * 여럿이라 이후 조회가 전부 터진다(단건을 기대하는 자리라). 그러면 시연을
     * <b>딱 한 번</b>밖에 못 한다 — 락 없음과 락 적용을 나란히 보여줘야 하는데
     * 그 사이에 앱을 다시 띄워야 하는 셈.
     *
     * 전액 환불(RELEASED)로 되돌린다. 몰수할 이유가 없다 — 구매자는 정상적으로
     * 요청했고, 여러 건이 생긴 건 우리 락이 없어서다. <b>귀책이 없으면 돈은
     * 원래 자리로</b>라는 이 프로젝트의 규칙이 여기에도 그대로 적용된다.
     */
    @Transactional
    public int releaseAllReservations(Long listingId, LocalDateTime now, String memo) {
        List<Deposit> held = depositRepository.findAllByListingIdAndStatus(
                listingId, DepositStatus.HELD);

        for (Deposit deposit : held) {
            depositService.release(deposit, now, memo);
        }
        listingRepository.findById(listingId)
                .ifPresent(listing -> listing.changeStatus(ListingStatus.OPEN));

        return held.size();
    }

    /** 판매자 철회로 예약이 무산됐을 때. 귀책이 없으니 전액 환불하고 예약자에게 알림 */
    @Transactional
    public void releaseReservation(Listing listing, LocalDateTime now, String memo,
                                   NotificationType type) {
        depositRepository.findByListingIdAndStatus(listing.getId(), DepositStatus.HELD)
                .ifPresent(deposit -> {
                    depositService.release(deposit, now, memo);
                    notificationService.notify(deposit.getMember(), type,
                            "판매자가 판매를 철회했습니다",
                            "'" + listing.getTicket().getTitle() + "' 예약금 " + deposit.getAmount()
                                    + "원을 전액 환불했습니다.",
                            "DEPOSIT", deposit.getId());
                });
    }

    // ══════════════════════════════════════════════════════════════

    /** 예약이 막힌 이유를 상태에 맞게 골라줌. "한발 늦었다"와 "살 수 있는 물건이 아니다"는 다른 얘기임 */
    private Error reservationBlockedReason(Listing listing) {
        return switch (listing.getStatus()) {
            case RESERVED -> Error.ALREADY_RESERVED;
            case IN_ESCROW, COMPLETED -> Error.ALREADY_SOLD;
            default -> Error.LISTING_NOT_OPEN;
        };
    }

    private Deposit findActiveReservation(Long listingId) {
        return depositRepository.findByListingIdAndStatus(listingId, DepositStatus.HELD)
                .orElseThrow(() -> new ResponseException(Error.RESERVATION_NOT_FOUND,
                        "판매 ID " + listingId));
    }

    private Listing findListing(Long id) {
        return listingRepository.findWithDetailById(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "판매 ID " + id));
    }

    /**
     * 락을 걸고 판매 건을 가져옴 (SELECT ... FOR UPDATE).
     *
     * 여기부터 트랜잭션이 끝날 때까지 이 행은 다른 트랜잭션이 못 건드림.
     * 뒤따르는 요청들은 실패하는 게 아니라 <b>기다렸다가</b> 바뀐 상태를 보고
     * ALREADY_RESERVED로 떨어진다 — 그게 락 적용/미적용의 눈에 보이는 차이다.
     */
    private Listing findListingForUpdate(Long id) {
        return listingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "판매 ID " + id));
    }

    /** 락 여부만 갈리고 나머지는 같음. 시뮬레이터가 두 경로를 같은 코드로 비교할 수 있게 */
    private Member findMember(String id, boolean useLock) {
        return (useLock ? memberRepository.findByIdForUpdate(id) : memberRepository.findById(id))
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "회원 ID " + id));
    }

    private Escrow findEscrow(Long escrowId) {
        return escrowRepository.findWithDetailById(escrowId)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "거래 ID " + escrowId));
    }
}
