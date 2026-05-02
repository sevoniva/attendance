package com.attendance.audit.service;

import com.attendance.audit.api.AttendanceApiController.SourceSheetPreview;
import com.attendance.audit.model.AttendanceDataset;
import com.attendance.audit.model.DailySummary;
import com.attendance.audit.model.DailySummaryRow;
import com.attendance.audit.model.DayRecord;
import com.attendance.audit.model.DetailRow;
import com.attendance.audit.model.EmployeeRecord;
import com.attendance.audit.model.EmployeeRule;
import com.attendance.audit.model.SummaryRow;
import com.attendance.audit.repository.EmployeeRuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*~\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\d{2}:\\d{2}");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime EARLY_MORNING_CUTOFF = LocalTime.of(6, 0);
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.CHINA);
    private static final String DEFAULT_SHEET_NAME = "考勤记录";
    private static final String RULES_FILE_NAME = "employee_rules.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EmployeeRuleRepository ruleRepository;

    public AttendanceService(EmployeeRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @PostConstruct
    public void init() {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        migrateOldRules(baseDirectory);
        migrateLunchTypeToBooleans();
    }

    public AttendanceDataset loadDatasetFromDirectory(Path directory) {
        Path source = findSourceFile(directory)
                .orElseThrow(() -> new IllegalStateException("当前目录未找到 .xls 或 .xlsx 考勤文件"));
        return loadDataset(source);
    }

    private void migrateOldRules(Path baseDirectory) {
        Path rulesFile = baseDirectory.resolve(RULES_FILE_NAME);
        if (!Files.exists(rulesFile)) {
            return;
        }
        try {
            List<Map<String, Object>> oldRules = OBJECT_MAPPER.readValue(rulesFile.toFile(), new TypeReference<>() {});
            for (Map<String, Object> old : oldRules) {
                String id = String.valueOf(old.get("employeeId"));
                String name = String.valueOf(old.get("name"));
                boolean specialLunch = Boolean.TRUE.equals(old.get("specialLunch"));
                if (ruleRepository.findById(id).isEmpty()) {
                    EmployeeRule rule = new EmployeeRule(id, name, false, specialLunch, false);
                    ruleRepository.save(rule);
                }
            }
            Files.deleteIfExists(rulesFile);
        } catch (IOException exception) {
            // ignore migration errors
        }
    }

    private void migrateLunchTypeToBooleans() {
        for (EmployeeRule rule : ruleRepository.findAll()) {
            String type = rule.getLunchType();
            if (type == null || type.isBlank()) {
                continue;
            }
            boolean changed = false;
            if (type.contains("dormitory") && !rule.isDormitoryLunch()) {
                rule.setDormitoryLunch(true);
                changed = true;
            }
            if (type.contains("flexible") && !rule.isFlexibleLunch()) {
                rule.setFlexibleLunch(true);
                changed = true;
            }
            if (changed) {
                rule.setLunchType(null);
                ruleRepository.save(rule);
            }
        }
    }

    public List<EmployeeRule> listRules() {
        return ruleRepository.findAll().stream()
                .sorted(Comparator.comparingInt((EmployeeRule r) -> {
                    try {
                        return Integer.parseInt(r.getEmployeeId());
                    } catch (NumberFormatException e) {
                        return Integer.MAX_VALUE;
                    }
                }))
                .toList();
    }

    public EmployeeRule getOrCreateRule(String employeeId, String name) {
        EmployeeRule rule = ruleRepository.findById(employeeId).orElseGet(() -> {
            EmployeeRule r = applyDefaultRule(new EmployeeRule(employeeId, name, false, false, false));
            return ruleRepository.save(r);
        });
        if (name != null && !name.isBlank() && !name.equals(rule.getName())) {
            rule.setName(name);
            ruleRepository.save(rule);
        }
        return rule;
    }

    private EmployeeRule applyDefaultRule(EmployeeRule rule) {
        String name = rule.getName();
        if (name == null) return rule;
        // 任杰：厂区住宿 + 晚餐扣除
        if (name.contains("任杰")) {
            rule.setDormitoryLunch(true);
            rule.setDinnerDeduct(true);
        }
        // 王清玲：厂区住宿 + 弹性午休
        if (name.contains("王清玲")) {
            rule.setDormitoryLunch(true);
            rule.setFlexibleLunch(true);
        }
        // 梁宗茂：厂区住宿
        if (name.contains("梁宗茂")) {
            rule.setDormitoryLunch(true);
        }
        return rule;
    }

    public void syncRulesFromFile(Path sourceFile) {
        if (!Files.exists(sourceFile)) {
            return;
        }
        try (InputStream inputStream = Files.newInputStream(sourceFile);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheet(DEFAULT_SHEET_NAME);
            if (sheet == null) {
                return;
            }
            Set<String> activeIds = new java.util.HashSet<>();
            for (int rowIndex = 4; rowIndex <= sheet.getLastRowNum(); rowIndex += 2) {
                Row metaRow = sheet.getRow(rowIndex);
                if (metaRow == null) {
                    continue;
                }
                String employeeId = readCell(metaRow, 2);
                if (!employeeId.isBlank()) {
                    activeIds.add(employeeId);
                }
            }
            for (EmployeeRule rule : ruleRepository.findAll()) {
                if (!activeIds.contains(rule.getEmployeeId())) {
                    ruleRepository.delete(rule);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("同步人员规则失败: " + sourceFile.getFileName(), exception);
        }
    }

    public void updateRule(String employeeId, boolean dormitoryLunch, boolean flexibleLunch, boolean dinnerDeduct) {
        EmployeeRule rule = ruleRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalStateException("未找到人员: " + employeeId));
        rule.setDormitoryLunch(dormitoryLunch);
        rule.setFlexibleLunch(flexibleLunch);
        rule.setDinnerDeduct(dinnerDeduct);
        ruleRepository.save(rule);
    }

    public AttendanceDataset loadDataset(Path sourceFile) {
        if (!Files.exists(sourceFile)) {
            throw new IllegalStateException("文件不存在: " + sourceFile.getFileName());
        }

        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        migrateOldRules(baseDirectory);

        try (InputStream inputStream = Files.newInputStream(sourceFile);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheet(DEFAULT_SHEET_NAME);
            if (sheet == null) {
                throw new IllegalStateException("未找到 sheet: " + DEFAULT_SHEET_NAME);
            }

            LocalDate[] range = parseDateRange(readCell(sheet.getRow(2), 2));
            List<EmployeeRecord> employees = new ArrayList<>();

            for (int rowIndex = 4; rowIndex <= sheet.getLastRowNum(); rowIndex += 2) {
                Row metaRow = sheet.getRow(rowIndex);
                if (metaRow == null) {
                    continue;
                }

                String employeeId = readCell(metaRow, 2);
                String name = readCell(metaRow, 10);
                String department = readCell(metaRow, 20);
                if (employeeId.isBlank() || name.isBlank()) {
                    continue;
                }

                Row rawRow = sheet.getRow(rowIndex + 1);
                List<String> rawDayCells = new ArrayList<>();
                int columnCount = rawRow == null ? 0 : Math.max(rawRow.getLastCellNum(), 0);
                for (int col = 0; col < columnCount; col++) {
                    rawDayCells.add(readCell(rawRow, col));
                }

                EmployeeRule rule = getOrCreateRule(employeeId, name);
                employees.add(new EmployeeRecord(
                        employeeId,
                        name,
                        department,
                        rule.isDormitoryLunch(),
                        rule.isFlexibleLunch(),
                        rule.isDinnerDeduct(),
                        buildDayRecords(range[0], rawDayCells, rule.isDormitoryLunch(), rule.isFlexibleLunch(), rule.isDinnerDeduct())
                ));
            }

            migrateOldRules(baseDirectory);
            return new AttendanceDataset(sourceFile, range[0], range[1], employees);
        } catch (IOException exception) {
            throw new IllegalStateException("读取考勤文件失败: " + sourceFile.getFileName(), exception);
        }
    }

    public Path uploadDirectory(Path baseDirectory) {
        return baseDirectory.resolve("uploads");
    }

    public List<Path> listSourceFiles(Path baseDirectory) {
        Path uploadDirectory = uploadDirectory(baseDirectory);
        Map<String, Path> files = new LinkedHashMap<>();
        if (Files.exists(uploadDirectory)) {
            try (var stream = Files.list(uploadDirectory)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isExcelFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(path -> files.put(path.getFileName().toString(), path));
            } catch (IOException exception) {
                throw new IllegalStateException("读取上传文件列表失败", exception);
            }
        }

        if (files.isEmpty()) {
            findSourceFile(baseDirectory).ifPresent(path -> files.put(path.getFileName().toString(), path));
        }

        return new ArrayList<>(files.values());
    }

    public Path resolveSourceFile(Path baseDirectory, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return listSourceFiles(baseDirectory).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("请先导入考勤文件"));
        }

        return listSourceFiles(baseDirectory).stream()
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到文件: " + fileName));
    }

    public Path storeUpload(Path baseDirectory, String originalFilename, InputStream inputStream) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalStateException("文件名不能为空");
        }
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xls") && !lowerName.endsWith(".xlsx") && !lowerName.endsWith(".csv")) {
            throw new IllegalStateException("只支持 .xls、.xlsx 和 .csv 文件");
        }

        Path uploadDirectory = uploadDirectory(baseDirectory);
        try {
            Files.createDirectories(uploadDirectory);
            clearDirectory(uploadDirectory);
            String safeName = originalFilename.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
            if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\")) {
                throw new IllegalStateException("文件名包含非法字符");
            }
            Path target = uploadDirectory.resolve(safeName).normalize();
            if (!target.startsWith(uploadDirectory.normalize())) {
                throw new IllegalStateException("文件名包含路径遍历字符");
            }

            byte[] content = inputStream.readAllBytes();
            validateExcelMagicBytes(content, lowerName);
            Files.write(target, content);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("上传文件保存失败", exception);
        }
    }

    private void validateExcelMagicBytes(byte[] content, String lowerName) {
        if (content.length < 4) {
            throw new IllegalStateException("文件内容过短，无法识别格式");
        }
        boolean isZip = content[0] == 0x50 && content[1] == 0x4B;
        boolean isOle2 = content[0] == (byte) 0xD0 && content[1] == (byte) 0xCF
                      && content[2] == (byte) 0x11 && content[3] == (byte) 0xE0;

        if (lowerName.endsWith(".xlsx") && !isZip) {
            throw new IllegalStateException("文件内容不是有效的 Excel 格式 (.xlsx)，可能已被篡改");
        }
        if (lowerName.endsWith(".xls") && !isOle2) {
            throw new IllegalStateException("文件内容不是有效的 Excel 格式 (.xls)，可能已被篡改");
        }
    }

    public void clearUploads(Path baseDirectory) {
        Path uploadDirectory = uploadDirectory(baseDirectory);
        if (Files.exists(uploadDirectory)) {
            clearDirectory(uploadDirectory);
        }
        // 同时删除根目录下的源文件（listSourceFiles 的空目录回退逻辑会找到它）
        findSourceFile(baseDirectory).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private void clearDirectory(Path directory) {
        try (var existing = Files.list(directory)) {
            existing.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("清空文件失败", exception);
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw new IllegalStateException("清空文件失败", ioException);
            }
            throw exception;
        }
    }

    public List<SummaryRow> buildSummaryRows(AttendanceDataset dataset) {
        return dataset.employees().stream()
                .map(employee -> {
                    String lunchLabel;
                    if (employee.dormitoryLunch() && employee.flexibleLunch()) {
                        lunchLabel = "厂区住宿+弹性";
                    } else if (employee.dormitoryLunch()) {
                        lunchLabel = "厂区住宿";
                    } else if (employee.flexibleLunch()) {
                        lunchLabel = "弹性午休";
                    } else {
                        lunchLabel = "普通";
                    }
                    if (employee.dinnerDeduct()) {
                        lunchLabel += "+晚餐扣";
                    }
                    return new SummaryRow(
                            employee.employeeId(),
                            employee.name(),
                            employee.department(),
                            lunchLabel,
                            employee.workedDays(),
                            employee.exceptionDays(),
                            employee.totalMinutes(),
                            roundHours(employee.totalMinutes()),
                            employee.totalUnits()
                    );
                })
                .sorted(Comparator.comparing(SummaryRow::totalUnits).reversed()
                        .thenComparing(SummaryRow::employeeId))
                .toList();
    }

    public Optional<EmployeeRecord> findEmployee(AttendanceDataset dataset, String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            return dataset.employees().stream().findFirst();
        }
        return dataset.employees().stream()
                .filter(employee -> employee.employeeId().equals(employeeId))
                .findFirst()
                .or(() -> dataset.employees().stream().findFirst());
    }

    public String exportCsv(AttendanceDataset dataset) {
        StringWriter writer = new StringWriter();
        writer.append('\ufeff');
        writer.append("工号,姓名,部门,日期,原始打卡,整理后打卡,时长(小时),工作时,午休扣减(分钟),午休规则,异常标记,计算依据\n");
        for (EmployeeRecord employee : dataset.employees()) {
            for (DayRecord day : employee.days()) {
                writer.append(csv(employee.employeeId())).append(',')
                        .append(csv(employee.name())).append(',')
                        .append(csv(employee.department())).append(',')
                        .append(csv(day.workDate().format(DATE_FORMATTER))).append(',')
                        .append(csv(day.rawText())).append(',')
                        .append(csv(formatPunches(day.punches()))).append(',')
                        .append(csv(String.format(Locale.US, "%.2f", roundHours(day.durationMinutes())))).append(',')
                        .append(csv(String.format(Locale.US, "%.1f", day.workUnits()))).append(',')
                        .append(csv(String.valueOf(day.lunchDeductionMinutes()))).append(',')
                        .append(csv(day.lunchRuleLabel())).append(',')
                        .append(csv(String.join("；", buildFlags(day)))).append(',')
                        .append(csv(day.calculationBasis()))
                        .append('\n');
            }
        }
        return writer.toString();
    }

    public byte[] exportExcel(AttendanceDataset dataset) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writeSummarySheet(workbook, buildSummaryRows(dataset));
            writeDailySummarySheet(workbook, buildDailySummary(dataset));
            writeDetailSheet(workbook, buildDetailRows(dataset));
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("导出 Excel 失败", exception);
        }
    }

    public List<SourceSheetPreview> buildSourceSheetPreviews(Path sourceFile) {
        try (InputStream inputStream = Files.newInputStream(sourceFile);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            List<SourceSheetPreview> previews = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<List<String>> previewRows = new ArrayList<>();
                int maxCols = 0;
                int rowCount = sheet.getLastRowNum() + 1;
                for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    int columnCount = row == null ? 0 : Math.max(row.getLastCellNum(), 0);
                    maxCols = Math.max(maxCols, columnCount);
                    List<String> rowValues = new ArrayList<>();
                    for (int colIndex = 0; colIndex < columnCount; colIndex++) {
                        rowValues.add(readCell(row, colIndex));
                    }
                    previewRows.add(rowValues);
                }
                previews.add(new SourceSheetPreview(
                        sheet.getSheetName(),
                        rowCount,
                        maxCols,
                        previewRows
                ));
            }
            return previews;
        } catch (IOException exception) {
            throw new IllegalStateException("读取原表结构失败", exception);
        }
    }

    public Map<String, Object> buildHeaderMetrics(AttendanceDataset dataset, List<SummaryRow> summaryRows) {
        int totalMinutes = summaryRows.stream().mapToInt(SummaryRow::totalMinutes).sum();
        long totalWorkedDays = summaryRows.stream().mapToLong(SummaryRow::workedDays).sum();
        long totalExceptionDays = summaryRows.stream().mapToLong(SummaryRow::exceptionDays).sum();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("employeeCount", summaryRows.size());
        metrics.put("totalWorkedDays", totalWorkedDays);
        metrics.put("totalHours", roundHours(totalMinutes));
        metrics.put("totalExceptionDays", totalExceptionDays);
        return metrics;
    }

    public String formatPunches(List<LocalDateTime> punches) {
        return punches.stream()
                .map(punch -> punch.toLocalTime().format(TIME_FORMATTER))
                .collect(Collectors.joining("  "));
    }

    public List<String> buildFlags(DayRecord day) {
        List<String> flags = new ArrayList<>();
        if (day.overnightMerged()) {
            flags.add("跨天并单");
        }
        if (day.incompletePunches()) {
            flags.add("打卡次数异常");
        }
        if (day.dinnerDeductionMinutes() > 0) {
            flags.add("晚餐扣除");
        }
        return flags;
    }

    public String joinNotes(List<String> notes) {
        return String.join("；", notes);
    }

    public List<DetailRow> buildDetailRows(AttendanceDataset dataset) {
        List<DetailRow> rows = new ArrayList<>();
        for (EmployeeRecord employee : dataset.employees()) {
            for (DayRecord day : employee.days()) {
                if (!day.rawText().isBlank() || !day.punches().isEmpty()) {
                    rows.add(new DetailRow(employee.employeeId(), employee.name(), employee.department(), day));
                }
            }
        }
        rows.sort(Comparator.comparing((DetailRow row) -> row.dayRecord().workDate())
                .thenComparing(DetailRow::employeeId));
        return rows;
    }

    public DailySummary buildDailySummary(AttendanceDataset dataset) {
        if (dataset.employees().isEmpty()) {
            return new DailySummary(List.of(), List.of());
        }
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM-dd");
        List<String> dates = dataset.employees().get(0).days().stream()
                .map(day -> day.workDate().format(dateFormatter))
                .toList();
        List<DailySummaryRow> rows = dataset.employees().stream()
                .map(employee -> {
                    List<Double> dailyHours = employee.days().stream()
                            .map(day -> roundHours(day.durationMinutes()))
                            .toList();
                    double totalHours = roundHours(employee.totalMinutes());
                    return new DailySummaryRow(employee.employeeId(), employee.name(), dailyHours, totalHours);
                })
                .sorted(Comparator.comparing(DailySummaryRow::employeeId))
                .toList();
        return new DailySummary(dates, rows);
    }

    private List<DayRecord> buildDayRecords(LocalDate startDate, List<String> rawDayCells, boolean dormitoryLunch, boolean flexibleLunch, boolean dinnerDeduct) {
        List<List<LocalDateTime>> parsedDays = new ArrayList<>();
        for (int i = 0; i < rawDayCells.size(); i++) {
            parsedDays.add(parseDayTimes(startDate.plusDays(i), rawDayCells.get(i)));
        }

        boolean[] overnightMerged = mergeOvernight(parsedDays);

        List<DayRecord> records = new ArrayList<>();
        for (int i = 0; i < rawDayCells.size(); i++) {
            LocalDate date = startDate.plusDays(i);
            List<LocalDateTime> punches = parsedDays.get(i);
            DurationResult durationResult = calculateDuration(punches, dormitoryLunch, flexibleLunch, dinnerDeduct, overnightMerged[i]);
            double workUnits = minutesToUnits(durationResult.durationMinutes());
            records.add(new DayRecord(
                    i + 1,
                    date,
                    rawDayCells.get(i),
                    List.copyOf(punches),
                    durationResult.durationMinutes(),
                    workUnits,
                    durationResult.lunchDeductionMinutes(),
                    durationResult.dinnerDeductionMinutes(),
                    durationResult.lunchRuleLabel(),
                    overnightMerged[i],
                    punches.size() == 1 || punches.size() == 2,
                    durationResult.notes(),
                    durationResult.calculationBasis(),
                    durationResult.morningMinutes(),
                    durationResult.afternoonMinutes(),
                    0.0,
                    0.0
            ));
        }
        return records;
    }

    private List<LocalDateTime> parseDayTimes(LocalDate workDate, String rawText) {
        Matcher matcher = TIME_PATTERN.matcher(rawText);
        List<LocalDateTime> punches = new ArrayList<>();
        String previous = null;
        while (matcher.find()) {
            String value = matcher.group();
            if (value.equals(previous)) {
                continue;
            }
            previous = value;
            String[] parts = value.split(":");
            punches.add(workDate.atTime(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }
        return punches;
    }

    private boolean[] mergeOvernight(List<List<LocalDateTime>> parsedDays) {
        boolean[] overnightMerged = new boolean[parsedDays.size()];
        for (int index = 1; index < parsedDays.size(); index++) {
            List<LocalDateTime> current = parsedDays.get(index);
            if (current.isEmpty()) {
                continue;
            }
            boolean movedAny = false;
            while (!current.isEmpty() && current.getFirst().toLocalTime().isBefore(EARLY_MORNING_CUTOFF)) {
                if (current.size() == 1) {
                    LocalDateTime moved = current.removeFirst();
                    parsedDays.get(index - 1).add(moved);
                    movedAny = true;
                    continue;
                }
                boolean hasDaytimeAfter = current.stream()
                        .skip(1)
                        .anyMatch(item -> !item.toLocalTime().isBefore(LocalTime.of(8, 0)));
                if (!hasDaytimeAfter) {
                    break;
                }
                LocalDateTime moved = current.removeFirst();
                parsedDays.get(index - 1).add(moved);
                movedAny = true;
            }
            if (movedAny) {
                parsedDays.get(index - 1).sort(Comparator.naturalOrder());
                current.sort(Comparator.naturalOrder());
                overnightMerged[index - 1] = true;
            }
        }
        return overnightMerged;
    }

    private DurationResult calculateDuration(List<LocalDateTime> punches, boolean dormitoryLunch, boolean flexibleLunch, boolean dinnerDeduct, boolean overnightMerged) {
        boolean twoHourLunch = dormitoryLunch || flexibleLunch;
        String defaultLabel;
        if (dormitoryLunch && flexibleLunch) {
            defaultLabel = "12:00-14:00 厂区住宿+弹性午休";
        } else if (dormitoryLunch) {
            defaultLabel = "12:00-14:00 厂区住宿午休";
        } else if (flexibleLunch) {
            defaultLabel = "12:00-14:00 弹性午休";
        } else {
            defaultLabel = "12:00-13:00 固定午休";
        }

        if (punches.isEmpty()) {
            return new DurationResult(0, 0, 0, defaultLabel, List.of(), "无打卡记录", 0, 0);
        }

        if (punches.size() == 1) {
            return new DurationResult(0, 0, 0, defaultLabel, List.of("仅1次打卡，无法计算工作时长"),
                    "仅1次打卡: " + punches.getFirst().toLocalTime().format(TIME_FORMATTER), 0, 0);
        }

        List<LocalDateTime> ordered = punches.stream().sorted().toList();
        LocalDateTime start = ordered.getFirst();
        LocalDateTime end = ordered.getLast();
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }

        int lunchDeductionMinutes;
        String lunchRuleLabel;
        List<String> notes = new ArrayList<>();
        LocalDateTime lunchStart = start.toLocalDate().atTime(12, 0);
        LocalDateTime lunchEnd;

        if (twoHourLunch) {
            lunchEnd = start.toLocalDate().atTime(14, 0);
        } else {
            lunchEnd = start.toLocalDate().atTime(13, 0);
        }

        if (flexibleLunch) {
            Optional<LocalDateTime> earliestLunchPunch = ordered.stream()
                    .filter(item -> {
                        LocalTime time = item.toLocalTime();
                        return !time.isBefore(LocalTime.of(12, 0)) && !time.isAfter(LocalTime.of(14, 0));
                    })
                    .findFirst();
            if (earliestLunchPunch.isPresent()) {
                lunchEnd = earliestLunchPunch.get();
                notes.add("按午间首个打卡提前结束午休");
            }
        }

        lunchDeductionMinutes = overlapMinutes(start, end, lunchStart, lunchEnd);

        if (dormitoryLunch && flexibleLunch) {
            lunchRuleLabel = notes.contains("按午间首个打卡提前结束午休")
                    ? "12:00-" + lunchEnd.toLocalTime().format(TIME_FORMATTER) + " 厂区住宿+弹性午休"
                    : "12:00-14:00 厂区住宿+弹性午休";
        } else if (dormitoryLunch) {
            lunchRuleLabel = "12:00-14:00 厂区住宿午休";
        } else if (flexibleLunch) {
            lunchRuleLabel = notes.contains("按午间首个打卡提前结束午休")
                    ? "12:00-" + lunchEnd.toLocalTime().format(TIME_FORMATTER) + " 弹性午休"
                    : "12:00-14:00 弹性午休";
        } else {
            lunchRuleLabel = "12:00-13:00 固定午休";
        }

        if (overnightMerged) {
            notes.add("次日凌晨打卡并入本日");
        }

        int dinnerDeductionMinutes = 0;
        if (dinnerDeduct && punches.size() >= 4) {
            dinnerDeductionMinutes = 60;
            notes.add("扣晚餐 60 分钟");
        }

        int totalMinutes = (int) Duration.between(start, end).toMinutes() - lunchDeductionMinutes - dinnerDeductionMinutes;
        if (totalMinutes < 0) {
            totalMinutes = 0;
        }

        int morningMinutes = 0;
        int afternoonMinutes = 0;
        if (start.isBefore(lunchStart)) {
            LocalDateTime morningEnd = lunchStart.isBefore(end) ? lunchStart : end;
            morningMinutes = (int) Duration.between(start, morningEnd).toMinutes();
        }
        if (end.isAfter(lunchEnd)) {
            LocalDateTime afternoonStart = lunchEnd.isAfter(start) ? lunchEnd : start;
            afternoonMinutes = (int) Duration.between(afternoonStart, end).toMinutes();
        }

        String basis;
        if (dinnerDeductionMinutes > 0) {
            basis = "%s - %s\n共 %d 分钟，扣午休 %d 分钟，扣晚餐 %d 分钟，计 %d 分钟\n上午 %d 分钟，下午 %d 分钟"
                    .formatted(
                            start.toLocalTime().format(TIME_FORMATTER),
                            end.toLocalDate().isAfter(start.toLocalDate())
                                    ? end.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                                    : end.toLocalTime().format(TIME_FORMATTER),
                            (int) Duration.between(start, end).toMinutes(),
                            lunchDeductionMinutes,
                            dinnerDeductionMinutes,
                            totalMinutes,
                            morningMinutes,
                            afternoonMinutes
                    );
        } else {
            basis = "%s - %s\n共 %d 分钟，扣午休 %d 分钟，计 %d 分钟\n上午 %d 分钟，下午 %d 分钟"
                    .formatted(
                            start.toLocalTime().format(TIME_FORMATTER),
                            end.toLocalDate().isAfter(start.toLocalDate())
                                    ? end.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                                    : end.toLocalTime().format(TIME_FORMATTER),
                            (int) Duration.between(start, end).toMinutes(),
                            lunchDeductionMinutes,
                            totalMinutes,
                            morningMinutes,
                            afternoonMinutes
                    );
        }

        return new DurationResult(totalMinutes, lunchDeductionMinutes, dinnerDeductionMinutes, lunchRuleLabel, List.copyOf(notes), basis, morningMinutes, afternoonMinutes);
    }

    private int overlapMinutes(LocalDateTime spanStart, LocalDateTime spanEnd, LocalDateTime breakStart, LocalDateTime breakEnd) {
        LocalDateTime overlapStart = spanStart.isAfter(breakStart) ? spanStart : breakStart;
        LocalDateTime overlapEnd = spanEnd.isBefore(breakEnd) ? spanEnd : breakEnd;
        if (!overlapEnd.isAfter(overlapStart)) {
            return 0;
        }
        return (int) Duration.between(overlapStart, overlapEnd).toMinutes();
    }

    private double minutesToUnits(int minutes) {
        int wholeHours = minutes / 60;
        int remainder = minutes % 60;
        return wholeHours + (remainder / 20) * 0.5;
    }

    private double roundHours(int minutes) {
        return Math.round((minutes / 60.0) * 100.0) / 100.0;
    }

    private LocalDate[] parseDateRange(String value) {
        Matcher matcher = DATE_RANGE_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new IllegalStateException("无法识别统计日期: " + value);
        }
        return new LocalDate[]{
                LocalDate.parse(matcher.group(1), DATE_FORMATTER),
                LocalDate.parse(matcher.group(2), DATE_FORMATTER)
        };
    }

    private Optional<Path> findSourceFile(Path directory) {
        try (var files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(this::isExcelFile)
                    .sorted()
                    .findFirst();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描考勤文件失败", exception);
        }
    }

    private boolean isExcelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xls") || name.endsWith(".xlsx");
    }

    private void writeSummarySheet(XSSFWorkbook workbook, List<SummaryRow> summaryRows) {
        Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName("人员汇总"));
        String[] headers = { "工号", "姓名", "部门", "午休类型", "出勤天数", "异常天数", "总分钟", "总小时", "总工作时" };
        int[] colWidths = { 10, 14, 16, 18, 12, 12, 12, 12, 12 };
        boolean[] numericCols = { false, false, false, false, true, true, true, true, true };

        createStyledHeaderRow(sheet, 0, headers, workbook);
        int rowIndex = 1;
        for (SummaryRow row : summaryRows) {
            String[] values = {
                    row.employeeId(), row.name(), row.department(), row.lunchLabel(),
                    String.valueOf(row.workedDays()), String.valueOf(row.exceptionDays()),
                    String.valueOf(row.totalMinutes()), String.valueOf(row.totalHours()),
                    String.valueOf(row.totalUnits())
            };
            createStyledDataRow(sheet, rowIndex++, values, numericCols, rowIndex % 2 == 0, false, workbook);
        }
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i] * 256 + 100);
        }
        sheet.createFreezePane(0, 1);
    }

    private void writeDetailSheet(XSSFWorkbook workbook, List<DetailRow> detailRows) {
        Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName("每日明细"));
        String[] headers = { "工号", "姓名", "部门", "日期", "原始打卡", "整理后打卡", "午休规则", "午休扣减", "晚餐扣减", "时长(分)", "时长(时)", "工时", "标记", "备注", "计算说明" };
        int[] colWidths = { 8, 12, 14, 14, 18, 20, 22, 10, 10, 10, 10, 10, 16, 20, 40 };
        boolean[] numericCols = { false, false, false, false, false, false, false, true, true, true, true, true, false, false, false };

        createStyledHeaderRow(sheet, 0, headers, workbook);
        int rowIndex = 1;
        for (DetailRow row : detailRows) {
            String[] values = {
                    row.employeeId(), row.name(), row.department(),
                    row.dayRecord().workDate().toString(),
                    row.dayRecord().rawText(),
                    formatPunches(row.dayRecord().punches()),
                    row.dayRecord().lunchRuleLabel(),
                    String.valueOf(row.dayRecord().lunchDeductionMinutes()),
                    String.valueOf(row.dayRecord().dinnerDeductionMinutes()),
                    String.valueOf(row.dayRecord().durationMinutes()),
                    String.valueOf(roundHours(row.dayRecord().durationMinutes())),
                    String.valueOf(row.dayRecord().workUnits()),
                    String.join("；", buildFlags(row.dayRecord())),
                    joinNotes(row.dayRecord().notes()),
                    row.dayRecord().calculationBasis()
            };
            boolean isException = row.dayRecord().overnightMerged() || row.dayRecord().incompletePunches();
            createStyledDataRow(sheet, rowIndex++, values, numericCols, rowIndex % 2 == 0, isException, workbook);
        }
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i] * 256 + 100);
        }
        sheet.createFreezePane(0, 1);
    }

    private void writeDailySummarySheet(XSSFWorkbook workbook, DailySummary dailySummary) {
        Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName("考勤汇总"));
        if (dailySummary.dates().isEmpty()) {
            return;
        }
        List<String> headerList = new ArrayList<>();
        headerList.add("序号");
        headerList.add("姓名");
        headerList.addAll(dailySummary.dates());
        headerList.add("总计（小时）");
        String[] headers = headerList.toArray(new String[0]);

        List<Integer> colWidthList = new ArrayList<>();
        colWidthList.add(8);
        colWidthList.add(14);
        for (int i = 0; i < dailySummary.dates().size(); i++) {
            colWidthList.add(10);
        }
        colWidthList.add(14);
        int[] colWidths = colWidthList.stream().mapToInt(Integer::intValue).toArray();

        boolean[] numericCols = new boolean[headers.length];
        numericCols[0] = true;
        numericCols[1] = false;
        for (int i = 2; i < headers.length - 1; i++) {
            numericCols[i] = true;
        }
        numericCols[headers.length - 1] = true;

        createStyledHeaderRow(sheet, 0, headers, workbook);
        int rowIndex = 1;
        int seq = 1;
        for (DailySummaryRow row : dailySummary.rows()) {
            List<String> values = new ArrayList<>();
            values.add(String.valueOf(seq++));
            values.add(row.name());
            for (Double hours : row.dailyHours()) {
                values.add(String.valueOf(hours));
            }
            values.add(String.valueOf(row.totalHours()));
            createStyledDataRow(sheet, rowIndex++, values.toArray(new String[0]), numericCols, rowIndex % 2 == 0, false, workbook);
        }
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i] * 256 + 100);
        }
        sheet.createFreezePane(2, 1);
    }

    private void createStyledHeaderRow(Sheet sheet, int rowIndex, String[] headers, XSSFWorkbook workbook) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(28);
        XSSFFont font = workbook.createFont();
        font.setBold(true);
        font.setColor(new XSSFColor(new byte[] { (byte) 255, (byte) 255, (byte) 255 }, null));
        font.setFontHeightInPoints((short) 11);
        font.setFontName("微软雅黑");

        XSSFCellStyle leftStyle = createBorderedStyle(workbook, font,
                new XSSFColor(new byte[] { (byte) 30, (byte) 58, (byte) 95 }, null),
                HorizontalAlignment.LEFT, VerticalAlignment.CENTER, true);
        XSSFCellStyle centerStyle = createBorderedStyle(workbook, font,
                new XSSFColor(new byte[] { (byte) 30, (byte) 58, (byte) 95 }, null),
                HorizontalAlignment.CENTER, VerticalAlignment.CENTER, true);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(i <= 3 ? leftStyle : centerStyle);
        }
    }

    private void createStyledDataRow(Sheet sheet, int rowIndex, String[] values, boolean[] numericCols, boolean even, boolean isException, XSSFWorkbook workbook) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(42);
        XSSFFont font = workbook.createFont();
        font.setFontHeightInPoints((short) 10);
        font.setFontName("微软雅黑");
        font.setColor(new XSSFColor(new byte[] { (byte) 15, (byte) 23, (byte) 42 }, null));

        XSSFColor bgColor;
        if (isException) {
            bgColor = new XSSFColor(new byte[] { (byte) 254, (byte) 242, (byte) 242 }, null);
        } else {
            bgColor = new XSSFColor(even
                    ? new byte[] { (byte) 248, (byte) 250, (byte) 252 }
                    : new byte[] { (byte) 255, (byte) 255, (byte) 255 }, null);
        }

        XSSFCellStyle leftStyle = createBorderedStyle(workbook, font, bgColor, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, false);
        XSSFCellStyle rightStyle = createBorderedStyle(workbook, font, bgColor, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER, false);
        XSSFCellStyle wrapStyle = createBorderedStyle(workbook, font, bgColor, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, false);
        wrapStyle.setWrapText(true);

        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            if (i == values.length - 1) {
                cell.setCellStyle(wrapStyle);
            } else {
                cell.setCellStyle(numericCols[i] ? rightStyle : leftStyle);
            }
        }
    }

    private XSSFCellStyle createBorderedStyle(XSSFWorkbook workbook, XSSFFont font, XSSFColor fillColor,
                                               HorizontalAlignment hAlign, VerticalAlignment vAlign, boolean isHeader) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(fillColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(hAlign);
        style.setVerticalAlignment(vAlign);

        XSSFColor borderColor = new XSSFColor(new byte[] { (byte) 200, (byte) 204, (byte) 208 }, null);
        style.setBorderTop(BorderStyle.THIN);
        style.setTopBorderColor(borderColor);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(borderColor);
        style.setBorderLeft(BorderStyle.THIN);
        style.setLeftBorderColor(borderColor);
        style.setBorderRight(BorderStyle.THIN);
        style.setRightBorderColor(borderColor);

        return style;
    }

    private String readCell(Row row, int cellIndex) {
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(cellIndex);
        if (cell == null) {
            return "";
        }
        return FORMATTER.formatCellValue(cell).trim();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private record DurationResult(
            int durationMinutes,
            int lunchDeductionMinutes,
            int dinnerDeductionMinutes,
            String lunchRuleLabel,
            List<String> notes,
            String calculationBasis,
            int morningMinutes,
            int afternoonMinutes
    ) {
    }
}
