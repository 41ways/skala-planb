package com.skala.planbmarket.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.SessionHandler;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.ExpiryType;
import com.skala.planbmarket.domain.enums.TicketStatus;
import com.skala.planbmarket.dto.request.TicketRequests;
import com.skala.planbmarket.dto.response.TicketResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ParameterException;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.MemberRepository;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 티켓 서비스.
 *
 * 수정·삭제는 OWNED 상태에서만 됨. 판매 등록된 뒤에 만료 시각을 바꿀 수 있게 두면
 * 이미 걸려 있는 예약금과 결제 제한시간 계산이 전부 어긋나고, 최악엔 만료를 지난 시각으로
 * 바꿔서 거래를 강제로 무산시킬 수 있음. 그걸 되돌리는 로직까지 만드는 건 범위 밖이라
 * 아예 못 바꾸게 막는 쪽이 맞다고 봤음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final MemberRepository memberRepository;
    private final SessionHandler sessionHandler;

    @Transactional
    public TicketResponse create(TicketRequests.Create request) {
        String memberId = sessionHandler.requireLoginMemberId();
        Member owner = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "회원 ID " + memberId));

        validateDates(request.category(), request.eventAt(), request.validFrom(), request.validUntil());

        Ticket ticket = Ticket.builder()
                .owner(owner)
                .category(request.category())
                .title(request.title())
                .originalPrice(request.originalPrice())
                .quantity(request.quantity())
                .eventAt(request.eventAt())
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .status(TicketStatus.OWNED)
                .build();

        // expiresAt과 expiryType은 @PrePersist에서 카테고리 기준으로 계산됨
        return TicketResponse.from(ticketRepository.save(ticket));
    }

    public PagedList<TicketResponse> list(int offset, int count, Category category) {
        // 만료가 가까운 것부터. 이 도메인에서 제일 급한 정보가 남은 시간임
        Sort sort = Sort.by(Sort.Direction.ASC, "expiresAt");
        Page<Ticket> page = (category == null)
                ? ticketRepository.findAll(Paging.of(offset, count, sort))
                : ticketRepository.findByCategoryIn(List.of(category), Paging.of(offset, count, sort));

        LocalDateTime now = LocalDateTime.now();
        return PagedList.of(page, offset, count, t -> TicketResponse.from(t, now));
    }

    public TicketResponse get(Long id) {
        return TicketResponse.from(findTicket(id));
    }

    @Transactional
    public TicketResponse update(Long id, TicketRequests.Update request) {
        Ticket ticket = findTicket(id);
        requireOwner(ticket);
        requireEditable(ticket);

        validateDates(ticket.getCategory(), request.eventAt(), request.validFrom(), request.validUntil());

        ticket.modify(request.title(), request.originalPrice(), request.quantity(),
                request.eventAt(), request.validFrom(), request.validUntil());

        // 저장 시점의 @PreUpdate에서 expiresAt이 다시 계산됨
        return TicketResponse.from(ticket);
    }

    @Transactional
    public void delete(Long id) {
        Ticket ticket = findTicket(id);
        requireOwner(ticket);
        requireEditable(ticket);
        ticketRepository.delete(ticket);
    }

    /**
     * 기한 연장. 기프티콘(EXTENDABLE)만 됨.
     *
     * 지금 기한보다 앞당기는 건 막음. "연장"인데 줄어들면 만료를 앞당기는 셈이고,
     * 판매 중인 티켓이면 거래 조건을 판매자가 일방적으로 나쁘게 바꾸는 게 됨.
     */
    @Transactional
    public TicketResponse extend(Long id, TicketRequests.Extend request) {
        Ticket ticket = findTicket(id);
        requireOwner(ticket);

        if (ticket.getCategory().getExpiryType() != ExpiryType.EXTENDABLE) {
            throw new ResponseException(Error.EXTEND_NOT_SUPPORTED,
                    ticket.getCategory().getDisplayName());
        }
        if (!request.extendedUntil().atStartOfDay().isAfter(ticket.getExpiresAt().toLocalDate().atStartOfDay())) {
            throw new ParameterException("extendedUntil",
                    "지금 기한(" + ticket.getExpiresAt().toLocalDate() + ")보다 뒤여야 합니다");
        }

        ticket.extendUntil(request.extendedUntil());
        return TicketResponse.from(ticket);
    }

    /**
     * 카테고리마다 필요한 날짜가 달라서 여기서 따짐.
     *
     * 애노테이션으로는 못 하는 검증임. @NotNull을 eventAt에 걸면 전시·호텔이 막히고,
     * 안 걸면 영화가 날짜 없이 등록됨. 카테고리를 읽어야만 판정이 됨.
     */
    private void validateDates(Category category, LocalDateTime eventAt,
                               LocalDate validFrom, LocalDate validUntil) {
        Map<String, String> errors = new LinkedHashMap<>();

        // 카테고리 이름을 괄호에 넣는 건 조사 때문임. "영화은(는)" 같은 게 나오지 않게
        // 이름 뒤에 조사가 붙지 않는 문장 구조로 씀
        if (category.getExpiryType() == ExpiryType.POINT_IN_TIME) {
            if (eventAt == null) {
                errors.put("eventAt",
                        "시점 만료 카테고리(" + category.getDisplayName() + ")라 일시가 필요합니다");
            }
        } else {
            if (validUntil == null) {
                errors.put("validUntil",
                        "기간 만료 카테고리(" + category.getDisplayName() + ")라 종료일이 필요합니다");
            }
            if (validFrom != null && validUntil != null && validFrom.isAfter(validUntil)) {
                errors.put("validFrom", "시작일이 종료일보다 뒤일 수 없습니다");
            }
            if (validUntil != null && validUntil.isBefore(LocalDate.now())) {
                errors.put("validUntil", "이미 지난 날짜로는 등록할 수 없습니다");
            }
        }

        if (!errors.isEmpty()) {
            throw new ParameterException(errors);
        }
    }

    private Ticket findTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "티켓 ID " + id));
    }

    private void requireOwner(Ticket ticket) {
        String memberId = sessionHandler.requireLoginMemberId();
        if (!ticket.getOwner().getId().equals(memberId)) {
            throw new ResponseException(Error.NO_PERMISSION, "본인 티켓이 아닙니다");
        }
    }

    private void requireEditable(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.OWNED) {
            throw new ResponseException(Error.TICKET_NOT_EDITABLE,
                    "현재 상태: " + ticket.getStatus());
        }
    }
}
