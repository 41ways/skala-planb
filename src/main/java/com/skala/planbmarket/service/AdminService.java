package com.skala.planbmarket.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.dto.request.AdminRequests;
import com.skala.planbmarket.dto.response.ConcurrencyTestResponse;
import com.skala.planbmarket.dto.response.IntegrityCheckResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.DepositRepository;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.LedgerRepository;
import com.skala.planbmarket.repository.ListingRepository;
import com.skala.planbmarket.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리·검증 서비스.
 *
 * 정합성 검증이 이 프로젝트의 핵심 장치임. 원장을 append-only로 쌓는 이유가
 * 바로 이걸 할 수 있게 하려는 거고, 반대로 이 검증이 없으면 원장은 그냥 로그일 뿐임.
 *
 * 검증이 실패했다는 건 어딘가에 버그가 있다는 뜻이고, 그 버그를 찾아가는 과정이
 * 곧 이 설계가 값을 하는 순간임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    /** 시뮬레이션이 이 시간 안에 안 끝나면 어딘가 물린 것으로 보고 넘어감 */
    private static final long SIMULATION_TIMEOUT_SECONDS = 30;

    private final LedgerRepository ledgerRepository;
    private final MemberRepository memberRepository;
    private final EscrowRepository escrowRepository;
    private final DepositRepository depositRepository;
    private final ListingRepository listingRepository;
    private final EscrowService escrowService;
    private final LedgerService ledgerService;

    /**
     * 동시성 테스트. 같은 판매 건에 N개 스레드가 동시에 예약을 건다.
     *
     * <p><b>왜 이게 필요한가.</b> 락이 필요하다는 건 코드를 읽어서는 증명이 안 된다.
     * 없을 때 실제로 깨지는 걸 보여줘야 한다. 그래서 락을 끄고 켜는 스위치를 두고
     * <b>나머지 코드 경로는 완전히 같게</b> 만들었다 — 바뀌는 건 조회 메서드 두 줄뿐.
     *
     * <p><b>이 메서드에 트랜잭션을 안 건 이유.</b> 클래스에 readOnly 트랜잭션이 걸려
     * 있는데 그대로 두면 뒷정리(쓰기)가 막힌다. 그리고 스레드들은 어차피 부모
     * 트랜잭션을 물려받지 않는다 — 각자 EscrowService를 부르면서 자기 트랜잭션을 연다.
     * 여기서 트랜잭션을 열어두면 스레드가 도는 내내 커넥션 하나를 붙들고만 있게 된다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConcurrencyTestResponse simulateConcurrent(AdminRequests.SimulateConcurrent request) {
        Long listingId = request.listingId();
        int threadCount = request.threadCount();
        boolean useLock = request.useLock();

        Listing listing = listingRepository.findWithDetailById(listingId)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "판매 ID " + listingId));
        if (!listing.isOpenForReservation()) {
            throw new ResponseException(Error.LISTING_NOT_OPEN,
                    "OPEN 상태여야 경합을 만들 수 있습니다. 현재 상태: " + listing.getStatus());
        }

        List<String> buyers = buyerPool(listing.getSeller().getId());
        if (buyers.isEmpty()) {
            throw new ResponseException(Error.DATA_NOT_FOUND, "구매자로 쓸 회원이 없습니다");
        }

        AtomicInteger success = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        // 출발 신호를 걸어두고 한 번에 푼다. 스레드를 만드는 데 걸리는 시간 때문에
        // 먼저 만들어진 쪽이 앞서 나가면 경합이 안 생기고, 경합이 없으면 락이 있으나
        // 없으나 결과가 같아서 아무것도 증명하지 못한다
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        long begin = System.nanoTime();
        for (int i = 0; i < threadCount; i++) {
            String buyerId = buyers.get(i % buyers.size());
            pool.execute(() -> {
                try {
                    start.await();
                    escrowService.reserve(listingId, buyerId, useLock);
                    success.incrementAndGet();
                } catch (ResponseException e) {
                    countFailure(failures, e.getError().name());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    countFailure(failures, "INTERRUPTED");
                } catch (Exception e) {
                    // 락이 없으면 여기로 오는 게 생긴다. 단건을 기대하는 조회가
                    // 여러 건을 만나거나, 같은 행을 동시에 고치다 타임아웃이 나거나.
                    // 사유별로 세어두면 "락 없이 돌리면 무슨 일이 나는가"가 그대로 드러난다
                    countFailure(failures, e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        try {
            done.await(SIMULATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        // ─── 판정 ───
        // 두 가지가 서로 다른 것을 본다.
        //   dataIntegrity  — 판매 건 하나에 예약이 하나뿐인가 (도메인 규칙)
        //   lostUpdates    — 회원 잔액이 원장 합과 같은가 (금전 정합성)
        // 락이 없으면 둘 다 깨지는데, 깨지는 방식이 다르다는 게 이 실험의 수확이다
        long reservationCount = depositRepository.countByListingIdAndStatus(listingId, DepositStatus.HELD);
        boolean dataIntegrity = reservationCount <= 1;
        boolean ledgerBalanced = ledgerRepository.sumAmountByEntryType(EntryType.DEBIT)
                == ledgerRepository.sumAmountByEntryType(EntryType.CREDIT);

        // ─── 뒷정리 ───
        int cleanedUp = escrowService.releaseAllReservations(listingId, LocalDateTime.now(),
                "동시성 테스트 정리 — 예약금 전액 환불");

        // 다른 빈(LedgerService)을 통해 부른다. 같은 클래스 안에 두고 this로 부르면
        // 스프링 AOP 프록시를 안 거쳐서 @Transactional이 안 걸린다 — 실제로 그렇게
        // 만들었다가 "고쳤다는데 안 고쳐지는" 증상을 봤음. NOTES 13-6 참조
        List<String> lostUpdates = ledgerService.reconcileBalances();

        int successCount = success.get();
        return new ConcurrencyTestResponse(
                listingId, threadCount, useLock,
                successCount, threadCount - successCount,
                reservationCount, dataIntegrity, ledgerBalanced,
                lostUpdates.isEmpty(), lostUpdates,
                failures.entrySet().stream()
                        .map(e -> new ConcurrencyTestResponse.FailureCount(e.getKey(), e.getValue().get()))
                        .sorted(Comparator.comparingInt(
                                ConcurrencyTestResponse.FailureCount::count).reversed())
                        .toList(),
                elapsedMs, cleanedUp,
                verdict(useLock, reservationCount, dataIntegrity, lostUpdates));
    }

    /** 결과를 한 줄로. 캡처에 그대로 들어갈 문장이라 무슨 일이 났는지가 바로 읽혀야 함 */
    private String verdict(boolean useLock, long reservationCount, boolean dataIntegrity,
                           List<String> lostUpdates) {
        if (!dataIntegrity || !lostUpdates.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (!dataIntegrity) {
                sb.append("동일 판매 건에 예약이 ").append(reservationCount)
                        .append("건 생성됨 — 중복 예약 발생. ");
            }
            if (!lostUpdates.isEmpty()) {
                sb.append("회원 ").append(lostUpdates.size())
                        .append("명의 잔액이 원장과 어긋남(lost update) — 같은 잔액을 동시에 읽고 ")
                        .append("각자 뺀 값을 써서 앞의 차감이 덮어써짐. 되돌려 놓았음. ");
            }
            sb.append("주의: 원장 차대(SUM(DEBIT)==SUM(CREDIT))는 내내 맞았다. ")
                    .append("돈이 새지 않는 것과 규칙이 지켜지는 것은 다른 문제다.");
            return sb.toString();
        }
        if (useLock) {
            return "1건만 성공, 나머지는 ALREADY_RESERVED. 뒤따르는 요청들은 실패한 게 아니라 "
                    + "락을 기다렸다가 바뀐 상태를 보고 정상적으로 거절됐다. "
                    + "잔액도 원장과 정확히 일치.";
        }
        return "락 없이 돌렸는데 아무것도 안 깨졌다. 경합이 실제로 일어나지 않았을 수 있으니 "
                + "스레드 수를 늘려서 다시 볼 것.";
    }

    private void countFailure(Map<String, AtomicInteger> failures, String reason) {
        failures.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 판매자를 뺀 회원들. 본인 티켓은 못 사므로 판매자가 섞이면 그 스레드는 경합이 아니라 400이 됨 */
    private List<String> buyerPool(String sellerId) {
        return memberRepository.findAll().stream()
                .map(Member::getId)
                .filter(id -> !id.equals(sellerId))
                .sorted()
                .toList();
    }

    public IntegrityCheckResponse integrityCheck() {
        // ─── 규칙 1: 회원별 잔액 == 원장 합 ───
        Map<String, Long> ledgerBalances = new HashMap<>();
        for (LedgerRepository.AccountBalance row : ledgerRepository.findAccountBalances(EntryType.CREDIT)) {
            ledgerBalances.put(row.getAccountId(), row.getBalance());
        }

        List<IntegrityCheckResponse.MismatchedMember> mismatched = new ArrayList<>();
        for (Member member : memberRepository.findAll()) {
            // 원장이 아예 없는 회원은 잔액이 0이어야 맞음. 가입만 하고 충전 안 한 경우
            long fromLedger = ledgerBalances.getOrDefault(member.getId(), 0L);
            if (member.getBalance() != fromLedger) {
                mismatched.add(new IntegrityCheckResponse.MismatchedMember(
                        member.getId(), member.getBalance(), fromLedger,
                        member.getBalance() - fromLedger));
            }
        }

        // ─── 규칙 2: 전체 차대 일치 ───
        long totalDebit = ledgerRepository.sumAmountByEntryType(EntryType.DEBIT);
        long totalCredit = ledgerRepository.sumAmountByEntryType(EntryType.CREDIT);

        // ─── 규칙 3: 보관 계정 잔액 == 진행 중 거래 금액 합 ───
        long escrowPoolBalance = ledgerRepository.balanceOf(
                SystemAccount.ESCROW_POOL.name(), EntryType.CREDIT);
        long heldEscrowTotal = escrowRepository.sumHeldAmount(EscrowStatus.HOLDING);

        // ─── 고아 검사: 돈이 움직였다고 기록된 건 있는데 원장에 흔적이 없는 경우 ───
        // 잔액 합계는 맞는데 개별 기록이 빠진 상황을 잡음. 위 세 규칙은 총액만 보기 때문에
        // "기록을 아예 안 남기고 잔액도 안 건드린" 유령 데이터는 못 걸러냄
        long orphanEscrows = 0;
        for (Escrow escrow : escrowRepository.findAll()) {
            if (!ledgerRepository.existsByRefTypeAndRefIdAndReason(
                    "ESCROW", escrow.getId(), LedgerReason.PURCHASE)) {
                orphanEscrows++;
            }
        }

        long orphanDeposits = 0;
        for (Deposit deposit : depositRepository.findAll()) {
            if (!ledgerRepository.existsByRefTypeAndRefIdAndReason(
                    "DEPOSIT", deposit.getId(), LedgerReason.DEPOSIT_HOLD)) {
                orphanDeposits++;
            }
        }

        // ─── 예약금 보관 계정도 같은 방식으로 대조 ───
        long depositPoolBalance = ledgerRepository.balanceOf(
                SystemAccount.DEPOSIT_POOL.name(), EntryType.CREDIT);
        long heldDepositTotal = depositRepository.sumHeldAmount(DepositStatus.HELD);

        boolean memberBalanceMatch = mismatched.isEmpty();
        boolean ledgerBalanced = totalDebit == totalCredit;
        boolean escrowPoolMatch = escrowPoolBalance == heldEscrowTotal;
        boolean depositPoolMatch = depositPoolBalance == heldDepositTotal;

        return new IntegrityCheckResponse(
                memberBalanceMatch && ledgerBalanced && escrowPoolMatch && depositPoolMatch
                        && orphanEscrows == 0 && orphanDeposits == 0,
                memberBalanceMatch,
                mismatched,
                ledgerBalanced,
                totalDebit,
                totalCredit,
                escrowPoolMatch,
                escrowPoolBalance,
                heldEscrowTotal,
                depositPoolMatch,
                depositPoolBalance,
                heldDepositTotal,
                orphanEscrows,
                orphanDeposits,
                ledgerRepository.balanceOf(SystemAccount.PLATFORM.name(), EntryType.CREDIT),
                LocalDateTime.now());
    }
}
