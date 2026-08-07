package com.skala.planbmarket.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.SessionHandler;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.ListingStatus;
import com.skala.planbmarket.domain.enums.NotificationType;
import com.skala.planbmarket.domain.enums.TicketStatus;
import com.skala.planbmarket.dto.request.ListingRequests;
import com.skala.planbmarket.dto.response.ListingResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ParameterException;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.ListingRepository;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 판매 등록 서비스.
 *
 * 티켓 수량을 통째로 파는 구조라 가격은 희망가 하나면 됨.
 * 구매자는 예약금을 걸고(RESERVED) 제한시간 안에 결제해야 거래가 성립함.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingService {

    /** 목록에 노출되는 상태들. 예약 중인 것도 보여줌 — 곧 풀릴 수 있어서 지켜볼 가치가 있음 */
    private static final List<ListingStatus> BROWSABLE =
            List.of(ListingStatus.OPEN, ListingStatus.RESERVED);

    private static final List<Category> ALL_CATEGORIES = Arrays.asList(Category.values());

    private final ListingRepository listingRepository;
    private final TicketRepository ticketRepository;
    private final EscrowRepository escrowRepository;
    private final EscrowService escrowService;
    private final SessionHandler sessionHandler;

    @Transactional
    public ListingResponse create(ListingRequests.Create request) {
        String memberId = sessionHandler.requireLoginMemberId();

        Ticket ticket = ticketRepository.findById(request.ticketId())
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND,
                        "티켓 ID " + request.ticketId()));

        if (!ticket.getOwner().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 티켓이 아닙니다");
        }
        if (ticket.getStatus() != TicketStatus.OWNED) {
            throw new ResponseException(Error.TICKET_ALREADY_LISTED, "현재 상태: " + ticket.getStatus());
        }
        if (ticket.isExpired(LocalDateTime.now())) {
            throw new ResponseException(Error.TICKET_EXPIRED, "만료 시각 " + ticket.getExpiresAt());
        }

        Listing listing = Listing.builder()
                .ticket(ticket)
                .seller(ticket.getOwner())
                .askingPrice(request.askingPrice())
                .status(ListingStatus.OPEN)
                .build();

        ticket.changeStatus(TicketStatus.LISTED);
        return ListingResponse.from(listingRepository.save(listing));
    }

    public PagedList<ListingResponse> list(int offset, int count, Category category, String sort) {
        Page<Listing> page = listingRepository.search(
                BROWSABLE,
                (category == null) ? ALL_CATEGORIES : List.of(category),
                Paging.of(offset, count, toSort(sort)));

        LocalDateTime now = LocalDateTime.now();
        return PagedList.of(page, offset, count, l -> ListingResponse.from(l, now));
    }

    public ListingResponse get(Long id) {
        return ListingResponse.from(findListing(id));
    }

    /**
     * 만료 임박 목록.
     *
     * 이미 지난 건 뺌. 스케줄러가 아직 안 돌아서 상태가 OPEN으로 남아 있을 수 있는데,
     * 그걸 "곧 만료됩니다"라고 보여주면 살 수 있는 것처럼 오해하게 됨.
     */
    public List<ListingResponse> expiringSoon(int hours) {
        if (hours < 1 || hours > 720) {
            throw new ParameterException("hours", "1 이상 720 이하여야 합니다");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Listing> listings = listingRepository
                .findByStatusInAndTicketExpiresAtBetweenOrderByTicketExpiresAtAsc(
                        BROWSABLE, now, now.plusHours(hours));
        return listings.stream().map(l -> ListingResponse.from(l, now)).toList();
    }

    /**
     * 판매자 철회.
     *
     * 결제까지 끝난 거래가 붙어 있으면 못 함. 구매자는 이미 돈을 냈는데 판매자가 일방적으로
     * 물릴 수 있으면 에스크로를 둔 의미가 없음.
     *
     * 예약이 걸려 있으면 예약금을 전액 돌려줌. 예약자는 아무 잘못이 없는데 판매자 사정으로
     * 무산된 거라 몰수할 이유가 없음. 반대로 판매자에게 위약금을 물리는 것도 이번 범위 밖임
     * (경고·제재 체계는 SPEC 11장에서 제외했음).
     */
    @Transactional
    public void withdraw(Long id) {
        String memberId = sessionHandler.requireLoginMemberId();
        Listing listing = findListing(id);

        if (!listing.getSeller().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 판매 건이 아닙니다");
        }
        if (!BROWSABLE.contains(listing.getStatus())) {
            throw new ResponseException(Error.LISTING_NOT_OPEN, "현재 상태: " + listing.getStatus());
        }
        if (escrowRepository.existsByListingIdAndStatusIn(id, List.of(EscrowStatus.HOLDING))) {
            throw new ResponseException(Error.ESCROW_EXISTS, "판매 ID " + id);
        }

        escrowService.releaseReservation(listing, LocalDateTime.now(),
                "판매자 철회 — 예약금 전액 환불", NotificationType.LISTING_WITHDRAWN);

        listing.changeStatus(ListingStatus.WITHDRAWN);
        listing.getTicket().changeStatus(TicketStatus.OWNED);
    }

    private Listing findListing(Long id) {
        return listingRepository.findWithDetailById(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "판매 ID " + id));
    }

    /** 정렬 키를 화이트리스트로 받음. 임의 문자열을 그대로 Sort에 넘기면 없는 컬럼에서 500이 남 */
    private Sort toSort(String sort) {
        if (sort == null || sort.isBlank() || "expiry".equals(sort)) {
            return Sort.by(Sort.Direction.ASC, "ticket.expiresAt");
        }
        if ("price".equals(sort)) {
            return Sort.by(Sort.Direction.ASC, "askingPrice");
        }
        if ("recent".equals(sort)) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        throw new ParameterException("sort", "expiry, price, recent 중 하나여야 합니다");
    }
}
