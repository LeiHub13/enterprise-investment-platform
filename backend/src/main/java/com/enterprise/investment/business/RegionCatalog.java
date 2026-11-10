package com.enterprise.investment.business;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class RegionCatalog {
    private static final Set<String> MUNICIPALITIES = Set.of("11", "12", "31", "50");
    private static final Map<String, String> PROVINCE_SHORT_NAMES = Map.of(
            "内蒙古自治区", "内蒙古", "广西壮族自治区", "广西", "西藏自治区", "西藏",
            "宁夏回族自治区", "宁夏", "新疆维吾尔自治区", "新疆",
            "香港特别行政区", "香港", "澳门特别行政区", "澳门");

    private final List<Region> regions;
    private final Map<String, Region> byCode = new LinkedHashMap<>();
    private final Map<String, List<Region>> byName = new LinkedHashMap<>();

    public RegionCatalog() throws IOException {
        var rows = new LinkedHashMap<String, String>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("regions/2025.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.matches("\\d{6} [\\p{IsHan}]+")) {
                    throw new IllegalStateException("Invalid region data");
                }
                rows.put(line.substring(0, 6), line.substring(7));
            }
        }

        var data = new ArrayList<Region>();
        rows.forEach((code, name) -> {
            if (code.endsWith("0000")) {
                data.add(new Region(code, name, "province", null));
                if (MUNICIPALITIES.contains(code.substring(0, 2))) {
                    data.add(new Region(code.substring(0, 2) + "0100", name, "city", code));
                }
            } else if (code.endsWith("00") || code.substring(2, 4).equals("90")) {
                data.add(new Region(code, name, "city", code.substring(0, 2) + "0000"));
            } else {
                data.add(new Region(code, name, "district", code.substring(0, 4) + "00"));
            }
        });
        regions = List.copyOf(data);
        for (Region region : regions) {
            byCode.put(region.code(), region);
            for (String alias : names(region)) {
                byName.computeIfAbsent(region.level() + ":" + alias, ignored -> new ArrayList<>()).add(region);
            }
        }
    }

    public List<Region> all() {
        return regions;
    }

    String code(String value, String level, String parent) {
        Region region = resolve(value, level, parent);
        return region == null ? Rows.trimmed(value) : region.code();
    }

    String nameOrLegacyCode(String value, String level, String parent) {
        String input = Rows.trimmed(value);
        if (input == null || input.matches("\\d+")) {
            return input;
        }
        Region region = resolve(input, level, parent);
        return region == null ? input : region.name();
    }

    List<String> variants(String value, String level, String parent) {
        String original = Rows.trimmed(value);
        if (original == null) {
            return List.of();
        }
        var variants = new LinkedHashSet<String>();
        variants.add(original);
        Region region = resolve(original, level, parent);
        if (region != null) {
            variants.add(region.code());
            variants.add(region.code() + "000");
            variants.add(region.code() + "000000");
            if (level.equals("province")) {
                variants.add(region.code().substring(0, 2));
            } else if (level.equals("city") && region.code().endsWith("00")) {
                variants.add(region.code().substring(0, 4));
            }
            variants.addAll(names(region));
        }
        return List.copyOf(variants);
    }

    private Region resolve(String value, String level, String parent) {
        String trimmed = Rows.trimmed(value);
        if (trimmed == null) {
            return null;
        }
        String code = trimmed;
        if (code.matches("\\d{2}") && level.equals("province")) {
            code += "0000";
        } else if (code.matches("\\d{4}") && level.equals("city")) {
            code += "00";
        } else if (code.matches("\\d{9}|\\d{12}")) {
            code = code.substring(0, 6);
        }
        Region exact = byCode.get(code);
        if (exact != null && exact.level().equals(level)) {
            return exact;
        }
        if (level.equals("city") && exact != null && exact.level().equals("province")
                && MUNICIPALITIES.contains(code.substring(0, 2))) {
            return byCode.get(code.substring(0, 2) + "0100");
        }
        Region parentRegion = level.equals("province") ? null : resolve(parent,
                level.equals("city") ? "province" : "city", null);
        List<Region> matching = byName.getOrDefault(level + ":" + trimmed, List.of());
        if (parentRegion != null) {
            matching = matching.stream().filter(item -> parentRegion.code().equals(item.parentCode())).toList();
        }
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static Set<String> names(Region region) {
        var names = new LinkedHashSet<String>();
        String name = region.name();
        names.add(name);
        String shortName = PROVINCE_SHORT_NAMES.get(name);
        if (shortName != null) {
            names.add(shortName);
        } else if (name.endsWith("地区")) {
            names.add(name.substring(0, name.length() - 2));
        } else if (name.endsWith("市") || name.endsWith("省") || name.endsWith("区")
                || name.endsWith("县") || name.endsWith("旗") || name.endsWith("盟")) {
            names.add(name.substring(0, name.length() - 1));
        }
        return names;
    }

    public record Region(String code, String name, String level, String parentCode) {}
}
