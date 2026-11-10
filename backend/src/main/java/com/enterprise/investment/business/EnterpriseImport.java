package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.ImportMapper;
import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.enterprise.investment.business.Rows.params;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
class EnterpriseImport {
    private static final int MAX_ROWS = 5_000;
    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("企业名称", "name"), Map.entry("统一社会信用代码", "uscc"),
            Map.entry("企业类型", "enterpriseType"), Map.entry("省份", "provinceCode"),
            Map.entry("省份名称", "provinceCode"), Map.entry("省份代码", "provinceCode"),
            Map.entry("城市", "cityCode"), Map.entry("城市名称", "cityCode"), Map.entry("城市代码", "cityCode"),
            Map.entry("区县", "districtCode"), Map.entry("区县名称", "districtCode"), Map.entry("区县代码", "districtCode"),
            Map.entry("行业代码", "industryCode"), Map.entry("行业名称", "industryName"),
            Map.entry("经营状态", "businessStatus"), Map.entry("注册资本", "registeredCapital"),
            Map.entry("成立日期", "establishedDate"), Map.entry("注册地址", "registeredAddress"),
            Map.entry("经营范围", "businessScope"), Map.entry("企业官网", "website"));

    private final ImportMapper imports;
    private final AuditMapper audits;
    private final EnterpriseService enterprises;
    private final TransactionTemplate transaction;
    private final Validator validator;
    private final RegionCatalog regions;

    EnterpriseImport(ImportMapper imports, AuditMapper audits, EnterpriseService enterprises,
                     TransactionTemplate transaction, Validator validator, RegionCatalog regions) {
        this.imports = imports;
        this.audits = audits;
        this.enterprises = enterprises;
        this.transaction = transaction;
        this.validator = validator;
        this.regions = regions;
    }

    Result run(MultipartFile file, long actor) {
        String filename = file.getOriginalFilename();
        if (filename == null || file.isEmpty() || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ResponseStatusException(BAD_REQUEST, "请选择非空的 .xlsx 文件");
        }
        String basename = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        if (basename.length() > 255) {
            throw new ResponseStatusException(BAD_REQUEST, "文件名最多 255 个字符");
        }
        try (XSSFWorkbook workbook = open(file)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Excel 文件中没有工作表");
            }
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw new ResponseStatusException(BAD_REQUEST, "单次最多导入 " + MAX_ROWS + " 行企业数据");
            }
            var formatter = new DataFormatter(Locale.ROOT);
            var columns = columns(sheet.getRow(0), formatter);
            var rows = new ArrayList<Row>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row != null && columns.values().stream().map(row::getCell)
                        .anyMatch(cell -> cell != null && !formatter.formatCellValue(cell).isBlank())) {
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "第一张工作表没有待导入的企业数据");
            }

            long source = enterprises.sourceId("EXCEL");
            var batchValues = params("source", source, "filename", basename, "total", rows.size(), "actor", actor);
            imports.insertBatch(batchValues);
            long batch = ((Number) batchValues.get("id")).longValue();
            var errors = new ArrayList<RowError>();
            try {
                for (Row row : rows) {
                    int rowNumber = row.getRowNum() + 1;
                    var raw = rawValues(row, columns, formatter);
                    String failure = saveRow(row, rowNumber, columns, raw, source, batch, actor);
                    if (failure != null) {
                        errors.add(new RowError(rowNumber, failure));
                    }
                }
                transaction.executeWithoutResult(status -> {
                    finish(batch, errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
                    audits.record(actor, "IMPORT", "IMPORT_BATCH", batch);
                });
            } catch (RuntimeException failure) {
                finish(batch, "FAILED");
                throw failure;
            }
            return new Result(batch, rows.size(), rows.size() - errors.size(), errors.size(), errors);
        } catch (IOException e) {
            throw new IllegalStateException("无法关闭 Excel 文件", e);
        }
    }

    private static XSSFWorkbook open(MultipartFile file) {
        try (var input = file.getInputStream()) {
            return new XSSFWorkbook(input);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(BAD_REQUEST, "无法读取此 .xlsx 文件", e);
        }
    }

    private String saveRow(Row row, int rowNumber, Map<String, Integer> columns, Map<String, String> raw,
                           long source, long batch, long actor) {
        String failure;
        try {
            var input = input(row, columns, raw);
            transaction.executeWithoutResult(status -> {
                enterprises.importRow(input, source, batch, rowNumber, raw, actor);
                imports.incrementSuccess(batch);
            });
            return null;
        } catch (ResponseStatusException e) {
            failure = e.getReason();
        } catch (DataIntegrityViolationException e) {
            failure = "该行与现有数据冲突或字段超出允许范围";
        }
        transaction.executeWithoutResult(status -> {
            enterprises.recordSource(source, null, batch, Integer.toString(rowNumber), raw, failure);
            imports.incrementFail(batch);
        });
        return failure;
    }

    private void finish(long batch, String status) {
        imports.finish(batch, status, LocalDateTime.now());
    }

    private static Map<String, Integer> columns(Row header, DataFormatter formatter) {
        var columns = new LinkedHashMap<String, Integer>();
        if (header != null) {
            for (Cell cell : header) {
                String label = formatter.formatCellValue(cell).strip().replace("\uFEFF", "");
                String field = HEADERS.getOrDefault(label, label);
                if (HEADERS.containsValue(field) && columns.putIfAbsent(field, cell.getColumnIndex()) != null) {
                    throw new ResponseStatusException(BAD_REQUEST, "存在重复的表头 " + label);
                }
            }
        }
        if (!columns.containsKey("name")) {
            throw new ResponseStatusException(BAD_REQUEST, "第一张工作表的首行需包含「企业名称」列");
        }
        return columns;
    }

    private static Map<String, String> rawValues(Row row, Map<String, Integer> columns, DataFormatter formatter) {
        var values = new LinkedHashMap<String, String>();
        columns.forEach((field, index) -> values.put(field, formatter.formatCellValue(row.getCell(index)).strip()));
        return values;
    }

    private EnterpriseInput input(Row row, Map<String, Integer> columns, Map<String, String> values) {
        for (int index : columns.values()) {
            var cell = row.getCell(index);
            if (cell != null && (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)) {
                throw new ResponseStatusException(BAD_REQUEST, "请将公式或错误单元格替换为实际值");
            }
        }
        var usccCell = cell(row, columns, "uscc");
        if (usccCell != null && usccCell.getCellType() == CellType.NUMERIC) {
            throw new ResponseStatusException(BAD_REQUEST, "统一社会信用代码须以文本格式填写");
        }
        String province = regions.nameOrLegacyCode(values.get("provinceCode"), "province", null);
        String city = regions.nameOrLegacyCode(values.get("cityCode"), "city", province);
        String district = regions.code(values.get("districtCode"), "district", city);
        var input = new EnterpriseInput(values.get("uscc"), values.get("name"), values.get("enterpriseType"),
                province, city, district,
                values.get("industryCode"), values.get("industryName"),
                capital(cell(row, columns, "registeredCapital"), values.get("registeredCapital")),
                date(cell(row, columns, "establishedDate"), values.get("establishedDate")),
                values.get("businessStatus"), values.get("registeredAddress"), values.get("businessScope"),
                values.get("website"), null);
        var issues = validator.validate(input);
        if (!issues.isEmpty()) {
            String message = issues.stream().map(issue -> {
                String field = issue.getPropertyPath().toString();
                String label = switch (field) {
                    case "provinceCode" -> "省份";
                    case "cityCode" -> "城市";
                    case "districtCode" -> "区县";
                    default -> HEADERS.entrySet().stream().filter(entry -> entry.getValue().equals(field))
                            .map(Map.Entry::getKey).findFirst().orElse(field);
                };
                return label + " " + issue.getMessage();
            }).sorted().collect(java.util.stream.Collectors.joining("，"));
            throw new ResponseStatusException(BAD_REQUEST, message);
        }
        return input;
    }

    private static Cell cell(Row row, Map<String, Integer> columns, String field) {
        var index = columns.get(field);
        return index == null ? null : row.getCell(index);
    }

    private static BigDecimal capital(Cell cell, String value) {
        if (cell == null || value == null || value.isBlank()) {
            return null;
        }
        try {
            return cell.getCellType() == CellType.NUMERIC
                    ? BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros()
                    : new BigDecimal(value).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(BAD_REQUEST, "注册资本请填写以元为单位的数值");
        }
    }

    private static LocalDate date(Cell cell, String value) {
        if (cell == null || value == null || value.isBlank()) {
            return null;
        }
        try {
            return cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate() : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, "成立日期请使用 Excel 日期或 YYYY-MM-DD");
        }
    }

    record Result(long batchId, int totalCount, int successCount, int failCount, List<RowError> errors) {}

    record RowError(int row, String message) {}
}
