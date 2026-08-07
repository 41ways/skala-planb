package com.skala.planbmarket.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PagedList;
import com.skala.planbmarket.common.Paging;
import com.skala.planbmarket.common.SessionHandler;
import com.skala.planbmarket.domain.entity.Ledger;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.dto.request.MemberRequests;
import com.skala.planbmarket.dto.response.LedgerResponse;
import com.skala.planbmarket.dto.response.MemberResponse;
import com.skala.planbmarket.dto.response.TicketResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.LedgerRepository;
import com.skala.planbmarket.repository.MemberRepository;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 서비스.
 *
 * 잔액을 바꾸는 건 충전 하나뿐인데 그것도 LedgerService를 거침.
 * 이 클래스에서 Member.balance를 직접 건드리는 코드는 한 줄도 없음 —
 * 잔액과 원장이 따로 놀 수 있는 지점을 만들지 않으려는 것.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final TicketRepository ticketRepository;
    private final LedgerRepository ledgerRepository;
    private final EscrowRepository escrowRepository;
    private final LedgerService ledgerService;
    private final SessionHandler sessionHandler;

    @Transactional
    public MemberResponse.Summary create(MemberRequests.Create request) {
        // PLATFORM 같은 이름으로 가입하면 원장의 시스템 계정과 ID가 겹쳐서
        // "이 계정이 회원인가 시스템인가"를 구분할 수 없게 됨
        if (SystemAccount.isSystemAccount(request.id().toUpperCase())) {
            throw new ResponseException(Error.RESERVED_ACCOUNT_ID, request.id());
        }
        if (memberRepository.existsById(request.id())) {
            throw new ResponseException(Error.DATA_DUPLICATED, "회원 ID " + request.id());
        }

        Member member = Member.builder()
                .id(request.id())
                .password(request.password())
                .balance(0L)
                .build();
        return MemberResponse.Summary.from(memberRepository.save(member));
    }

    @Transactional
    public MemberResponse.Summary login(MemberRequests.Login request) {
        Member member = memberRepository.findById(request.id())
                .orElseThrow(() -> new ResponseException(Error.LOGIN_FAILED));

        // 없는 ID와 틀린 비밀번호를 같은 오류로 돌려줌.
        // 나눠서 알려주면 "이 ID는 존재한다"는 정보가 새어 나감
        if (!member.getPassword().equals(request.password())) {
            throw new ResponseException(Error.LOGIN_FAILED);
        }

        sessionHandler.login(member.getId());
        return MemberResponse.Summary.from(member);
    }

    public void logout() {
        sessionHandler.logout();
    }

    public PagedList<MemberResponse.Summary> list(int offset, int count) {
        Page<Member> page = memberRepository.findAll(Paging.of(offset, count));
        return PagedList.of(page, offset, count, MemberResponse.Summary::from);
    }

    /** 상세는 보유 티켓까지 같이 내려줌 */
    public MemberResponse.Detail get(String id) {
        Member member = findMember(id);
        List<Ticket> tickets = ticketRepository.findByOwnerIdOrderByExpiresAtAsc(id);
        return MemberResponse.Detail.of(member, tickets.stream().map(TicketResponse::from).toList());
    }

    /**
     * 예치금 충전.
     *
     * 실제 결제는 없고 EXTERNAL 계정에서 돈이 들어오는 것으로 기록함. 그냥 잔액만
     * 올리면 그 순간 전체 차대가 깨져서, 어디서 온 돈인지 상대편 계정이 반드시 필요함.
     * 그래서 LedgerService를 통해서만 처리하고 여기서 balance를 직접 건드리지 않음.
     */
    @Transactional
    public MemberResponse.Summary charge(String id, MemberRequests.Charge request) {
        sessionHandler.requireSelf(id);
        Member member = findMember(id);

        ledgerService.transfer(SystemAccount.EXTERNAL.name(), id, request.amount(),
                LedgerReason.CHARGE, "CHARGE", null, "예치금 충전");

        return MemberResponse.Summary.from(member);
    }

    /** 내 원장 조회. 남의 돈 흐름은 볼 수 없음 */
    public PagedList<LedgerResponse> ledger(String id, int offset, int count) {
        sessionHandler.requireSelf(id);
        Page<Ledger> page = ledgerRepository.findByAccountIdOrderByIdDesc(id, Paging.of(offset, count));
        return PagedList.of(page, offset, count, LedgerResponse::from);
    }

    @Transactional
    public MemberResponse.Summary update(MemberRequests.Update request) {
        sessionHandler.requireSelf(request.id());

        Member member = findMember(request.id());
        member.changePassword(request.password());
        return MemberResponse.Summary.from(member);
    }

    /**
     * 회원 삭제.
     *
     * 원장이 남아 있으면 못 지움. 원장은 append-only라 회원을 지워도 기록은 그대로 남는데,
     * 그러면 "회원 없는 계정"이 생겨서 정합성 검증(회원 잔액 == 원장 합)이 검사할 대상을
     * 잃어버림. 돈이 오간 흔적을 지울 수 없다는 게 원장을 쌓는 이유이기도 하니,
     * 지우는 쪽을 막는 게 일관됨.
     */
    @Transactional
    public void delete(String id) {
        sessionHandler.requireSelf(id);
        Member member = findMember(id);

        if (ledgerRepository.existsByAccountId(id) || escrowRepository.existsByBuyerId(id)) {
            throw new ResponseException(Error.MEMBER_HAS_HISTORY, "회원 ID " + id);
        }

        memberRepository.delete(member);
        sessionHandler.logout();
    }

    private Member findMember(String id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "회원 ID " + id));
    }
}
