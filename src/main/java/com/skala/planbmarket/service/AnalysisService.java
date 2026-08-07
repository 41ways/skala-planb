package com.skala.planbmarket.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.RemainingBucket;
import com.skala.planbmarket.dto.response.CategorySummaryResponse;
import com.skala.planbmarket.dto.response.ExpiryLossResponse;
import com.skala.planbmarket.dto.response.PriceSuggestionResponse;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ParameterException;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.mapper.AnalysisMapper;
import com.skala.planbmarket.mapper.CategoryStatRow;
import com.skala.planbmarket.mapper.ExpiryLossRow;
import com.skala.planbmarket.mapper.RatioSampleRow;
import com.skala.planbmarket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 분석·통계 서비스 (MyBatis).
 *
 * SQL은 "표본이 이렇더라"까지만 답하고, 판단은 전부 여기서 함 —
 * 폴백을 어디까지 내려갈지, 실효율의 분모를 뭘로 볼지, 빈 카테고리를 채울지.
 * 정책을 SQL에 섞으면 나중에 바꿀 때 XML을 뒤져야 하고, 무엇보다 근거를 적어둘 자리가 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisService {

    /** 표본을 모으는 기간. 이보다 오래된 거래는 지금 시세와 무관하다고 봄 */
    private static final int SAMPLE_WINDOW_DAYS = 30;

    /**
     * 표본이 하나도 없을 때 쓰는 기본 비율.
     *
     * 근거가 있는 숫자는 아님. 다만 추천을 포기하고 null을 주면 화면이 빈칸이 되고
     * 사용자는 아무 도움도 못 받음. "정가보다는 확실히 싸게"라는 최소한의 방향만 주고,
     * 그 값이 표본 없이 나온 값이라는 걸 basis와 sampleCount로 같이 알림.
     */
    private static final double DEFAULT_RATIO = 0.70;

    private static final int LOSS_DAYS_MIN = 1;
    private static final int LOSS_DAYS_MAX = 90;

    private final AnalysisMapper analysisMapper;
    private final TicketRepository ticketRepository;

    /**
     * 가격 추천.
     *
     * 표본을 세 단계로 좁혀 내려감 — 카테고리+구간 → 카테고리 → 기본값.
     * 시드가 (카테고리 × 구간) 조합마다 1건씩이라 2단계 폴백이 실제로 타게 돼 있음.
     * 폴백은 "혹시 몰라서" 넣는 코드가 아니라 <b>정상 경로</b>라는 걸 검증할 수 있는 구조.
     */
    public PriceSuggestionResponse suggestPrice(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "티켓 ID " + ticketId));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(SAMPLE_WINDOW_DAYS);

        long hoursLeft = Duration.between(now, ticket.getExpiresAt()).toHours();
        RemainingBucket bucket = RemainingBucket.of(hoursLeft);
        Category category = ticket.getCategory();

        // 1단계 — 같은 카테고리, 같은 잔여시간 구간
        RatioSampleRow sample = analysisMapper.findRatioByCategoryAndBucket(
                category, bucket.getMinHours(), bucket.getMaxHours(), since);
        PriceSuggestionResponse.Basis basis = PriceSuggestionResponse.Basis.CATEGORY_BUCKET;

        // 2단계 — 구간을 풀고 카테고리 전체
        if (isEmpty(sample)) {
            sample = analysisMapper.findRatioByCategory(category, since);
            basis = PriceSuggestionResponse.Basis.CATEGORY;
        }

        // 3단계 — 표본 없음
        long sampleCount;
        double ratio;
        if (isEmpty(sample)) {
            basis = PriceSuggestionResponse.Basis.DEFAULT;
            sampleCount = 0;
            ratio = DEFAULT_RATIO;
        } else {
            sampleCount = sample.getSampleCount();
            ratio = sample.getAvgRatio();
        }

        long suggestedPrice = Math.round(ratio * ticket.getOriginalPrice());

        return new PriceSuggestionResponse(
                ticket.getId(),
                ticket.getTitle(),
                category,
                ticket.getOriginalPrice(),
                hoursLeft,
                bucket,
                bucket.getLabel(),
                sampleCount,
                round4(ratio),
                suggestedPrice,
                basis,
                basis.getDescription());
    }

    /**
     * 카테고리별 거래 현황.
     *
     * 티켓이 한 건도 없는 카테고리는 SQL에서 행 자체가 안 나옴. 그대로 내보내면
     * 화면의 막대 개수가 데이터에 따라 들쭉날쭉해지므로 여기서 0으로 채움.
     * SQL이 "없는 것"까지 만들어내게 하는 것보다 이쪽이 단순함.
     */
    public CategorySummaryResponse categorySummary() {
        Map<Category, CategoryStatRow> byCategory = new EnumMap<>(Category.class);
        for (CategoryStatRow row : analysisMapper.findCategoryStats()) {
            byCategory.put(row.getCategory(), row);
        }

        List<CategorySummaryResponse.Row> rows = new ArrayList<>();
        long totalTickets = 0;
        long totalTraded = 0;
        long totalExpired = 0;
        long totalTradedAmount = 0;
        long totalLostAmount = 0;

        for (Category category : Category.values()) {
            CategoryStatRow row = byCategory.get(category);

            long ticketCount = row == null ? 0 : row.getTicketCount();
            long tradedCount = row == null ? 0 : row.getTradedCount();
            long expiredCount = row == null ? 0 : row.getExpiredCount();
            long tradedAmount = row == null ? 0 : row.getTradedAmount();
            long lostAmount = row == null ? 0 : row.getLostAmount();
            double avgRatio = (row == null || row.getAvgRatio() == null) ? 0 : row.getAvgRatio();

            rows.add(new CategorySummaryResponse.Row(
                    category,
                    category.getDisplayName(),
                    category.getExpiryType(),
                    ticketCount,
                    tradedCount,
                    expiredCount,
                    expiryRate(tradedCount, expiredCount),
                    tradedAmount,
                    lostAmount,
                    round4(avgRatio)));

            totalTickets += ticketCount;
            totalTraded += tradedCount;
            totalExpired += expiredCount;
            totalTradedAmount += tradedAmount;
            totalLostAmount += lostAmount;
        }

        return new CategorySummaryResponse(rows, new CategorySummaryResponse.Totals(
                totalTickets, totalTraded, totalExpired,
                expiryRate(totalTraded, totalExpired),
                totalTradedAmount, totalLostAmount));
    }

    /**
     * 일별 실효 손실.
     *
     * 실효가 없던 날은 행이 안 나감. 0인 날을 채우는 건 그리는 쪽 일 —
     * API가 "조회 기간"과 "실제로 일어난 일"을 섞어서 내보내면 나중에 기간 규칙이
     * 바뀔 때 API와 화면을 둘 다 고쳐야 함.
     */
    public ExpiryLossResponse expiryLoss(int days) {
        if (days < LOSS_DAYS_MIN || days > LOSS_DAYS_MAX) {
            throw new ParameterException("days",
                    LOSS_DAYS_MIN + " 이상 " + LOSS_DAYS_MAX + " 이하여야 합니다");
        }

        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<ExpiryLossRow> rows = analysisMapper.findExpiryLoss(since);

        List<ExpiryLossResponse.Row> daily = new ArrayList<>();
        long totalExpired = 0;
        long totalListed = 0;
        long totalOriginal = 0;
        long totalMarket = 0;

        for (ExpiryLossRow row : rows) {
            daily.add(new ExpiryLossResponse.Row(
                    row.getLossDate(),
                    row.getExpiredCount(),
                    row.getListedCount(),
                    row.getOriginalLoss(),
                    row.getMarketLoss()));

            totalExpired += row.getExpiredCount();
            totalListed += row.getListedCount();
            totalOriginal += row.getOriginalLoss();
            totalMarket += row.getMarketLoss();
        }

        return new ExpiryLossResponse(days, since.toLocalDate(), daily,
                new ExpiryLossResponse.Totals(totalExpired, totalListed,
                        totalOriginal, totalMarket, totalExpired - totalListed));
    }

    /** 표본이 없거나 평균이 NULL이면 다음 단계로 */
    private boolean isEmpty(RatioSampleRow sample) {
        return sample == null || sample.getSampleCount() == 0 || sample.getAvgRatio() == null;
    }

    /**
     * 실효율 = 실효 / (양도완료 + 실효).
     *
     * 분모를 전체 티켓이 아니라 "결말이 난 티켓"으로 잡음. 아직 판매 중인 매물을 분모에
     * 넣으면 새 티켓이 등록될 때마다 실효율이 저절로 내려감 — 아무 일도 안 일어났는데
     * 지표가 좋아지는 건 지표가 아님.
     */
    private double expiryRate(long tradedCount, long expiredCount) {
        long settled = tradedCount + expiredCount;
        return settled == 0 ? 0 : round4((double) expiredCount / settled);
    }

    /** 소수점 넷째 자리까지. 0.8206896551724138 같은 값을 그대로 내보내지 않으려는 것 */
    private double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
