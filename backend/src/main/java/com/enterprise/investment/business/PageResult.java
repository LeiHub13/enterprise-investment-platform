package com.enterprise.investment.business;

import java.util.List;
import java.util.Map;

record PageResult(List<Map<String, Object>> items, long total) {
}
