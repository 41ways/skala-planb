package com.skala.planbmarket.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

import lombok.Getter;

/**
 * 페이징 목록 응답.
 *
 * totalCount를 같이 주는 이유는, 대시보드에서 "3건 중 1건"처럼 전체 대비 위치를
 * 보여줘야 하는데 현재 페이지만 받으면 그걸 알 수가 없어서임.
 */
@Getter
public class PagedList<T> {

    private final List<T> list;
    private final long totalCount;
    private final int offset;
    private final int count;

    private PagedList(List<T> list, long totalCount, int offset, int count) {
        this.list = list;
        this.totalCount = totalCount;
        this.offset = offset;
        this.count = count;
    }

    /** Page를 받아 엔티티를 응답 DTO로 바꾸면서 감쌈 */
    public static <E, T> PagedList<T> of(Page<E> page, int offset, int count, Function<E, T> mapper) {
        return new PagedList<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                offset,
                count);
    }
}
