package com.skala.planbmarket.domain.enums;

/**
 * 티켓 카테고리 8종.
 *
 * 각 카테고리가 만료 유형(어떻게 소멸하는가)을 필드로 들고 있음.
 * 카테고리가 달라도 만료 판정이 같으면 코드는 하나면 되니까, 판정 로직은 ExpiryType 쪽에 두고
 * 카테고리는 "어느 유형인지"만 알려주는 구조로 감.
 */
public enum Category {

    MOVIE("영화", ExpiryType.POINT_IN_TIME),
    CONCERT("콘서트", ExpiryType.POINT_IN_TIME),
    SPORTS("스포츠", ExpiryType.POINT_IN_TIME),
    EXHIBITION("전시·팝업", ExpiryType.DATE_RANGE),
    TRAIN("기차", ExpiryType.POINT_IN_TIME),
    FLIGHT("항공", ExpiryType.POINT_IN_TIME),
    HOTEL("호텔", ExpiryType.DATE_RANGE),
    GIFTICON("기프티콘", ExpiryType.EXTENDABLE);

    private final String displayName;
    private final ExpiryType expiryType;

    Category(String displayName, ExpiryType expiryType) {
        this.displayName = displayName;
        this.expiryType = expiryType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ExpiryType getExpiryType() {
        return expiryType;
    }
}
