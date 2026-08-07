package com.skala.planbmarket.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.skala.planbmarket.exception.ParameterException;

/**
 * offset/count 방식 요청을 Spring Data의 Pageable로 바꿔주는 변환기.
 *
 * API는 offset/count로 받는데(교재 규약) Spring Data는 page/size로 동작해서 변환이 필요함.
 * offset을 count로 나눠 페이지 번호를 만드는 방식이라, offset이 count의 배수가 아니면
 * 그 값을 품는 페이지의 시작으로 내려감. 예를 들어 offset=5, count=10이면 0~9를 돌려줌.
 *
 * 이 어긋남을 없애려면 Pageable 대신 네이티브 쿼리로 직접 OFFSET을 걸어야 하는데,
 * 목록 API가 전부 "0, 20, 40..." 식으로 넘어오는 구조라 얻는 것 대비 비용이 큼.
 * 대신 어긋난다는 사실을 여기 적어둠.
 */
public final class Paging {

    public static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;

    private Paging() {
    }

    public static PageRequest of(int offset, int count) {
        return of(offset, count, Sort.unsorted());
    }

    public static PageRequest of(int offset, int count, Sort sort) {
        if (offset < 0) {
            throw new ParameterException("offset", "0 이상이어야 합니다");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new ParameterException("count", "1 이상 " + MAX_COUNT + " 이하여야 합니다");
        }
        return PageRequest.of(offset / count, count, sort);
    }
}
