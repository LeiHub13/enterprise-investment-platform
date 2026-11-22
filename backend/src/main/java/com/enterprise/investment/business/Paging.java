package com.enterprise.investment.business;

import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

final class Paging {
    private Paging() {
    }

    static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "页码从 1 开始，每页可查询 1 到 100 条");
        }
        return (page - 1L) * size;
    }
}
