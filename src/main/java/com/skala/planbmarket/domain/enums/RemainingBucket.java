package com.skala.planbmarket.domain.enums;

/**
 * 잔여시간 구간.
 *
 * 가격 추천의 뼈대임. 같은 티켓이라도 만료가 코앞이면 헐값에 팔리고 여유가 있으면 제값을
 * 받는다는 게 이 도메인의 성질이라, "얼마나 남았는가"로 표본을 갈라야 추천가가 의미를 가짐.
 * 카테고리만으로 평균을 내면 급처분과 여유 매물이 한 통에 섞여서 아무한테도 안 맞는 값이 나옴.
 *
 * <p>경계는 <b>아래를 포함하고 위를 배제</b>함 — {@code min <= h < max}.
 * SPEC 2-6의 "D1(1~3일) / D3(3~7일)"은 3일과 7일이 어느 쪽에 속하는지가 겹쳐 있어서
 * 한쪽으로 확정한 것. 겹친 채로 두면 SQL의 CASE WHEN 순서에 따라 결과가 달라지는데,
 * 그건 정책이 아니라 사고임.
 *
 * <p>구간 판정을 여기 한 곳에만 두는 이유: 표본 쪽 경계(거래 시점의 잔여시간)와 내 티켓 쪽
 * 경계(지금의 잔여시간)가 반드시 같아야 함. SQL에도 CASE WHEN으로 적어두면 두 곳이 되고,
 * 한쪽만 고치는 순간 조용히 틀린 추천가가 나감. 그래서 SQL에는 경계를 파라미터로 넘김.
 */
public enum RemainingBucket {

    /** 24시간 미만 — 급처분 구간 */
    D0("24시간 미만", 0, 24),

    /** 1~3일 */
    D1("1~3일", 24, 72),

    /** 3~7일 */
    D3("3~7일", 72, 168),

    /** 7일 이상 — 여유 있을 때 */
    D7("7일 이상", 168, Integer.MAX_VALUE);

    private final String label;
    private final int minHours;
    private final int maxHours;

    RemainingBucket(String label, int minHours, int maxHours) {
        this.label = label;
        this.minHours = minHours;
        this.maxHours = maxHours;
    }

    public String getLabel() {
        return label;
    }

    public int getMinHours() {
        return minHours;
    }

    public int getMaxHours() {
        return maxHours;
    }

    /**
     * 남은 시간(시간 단위)으로 구간을 판정.
     *
     * 음수(이미 만료)는 D0으로 봄. 만료된 티켓에 추천가를 물어보는 건 의미가 없지만,
     * 여기서 예외를 던지면 "만료 직전"과 "방금 만료"가 다르게 취급돼서 경계가 또 하나 생김.
     * 가장 급한 구간으로 흘려보내는 게 자연스러움.
     */
    public static RemainingBucket of(long hoursLeft) {
        for (RemainingBucket bucket : values()) {
            if (hoursLeft < bucket.maxHours) {
                return bucket;
            }
        }
        return D7;
    }
}
