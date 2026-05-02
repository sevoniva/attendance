package com.attendance.audit.api;

import com.attendance.audit.model.AttendanceDataset;
import com.attendance.audit.model.DailySummary;
import com.attendance.audit.model.DetailRow;
import com.attendance.audit.model.EmployeeRecord;
import com.attendance.audit.model.EmployeeRule;
import com.attendance.audit.model.SummaryRow;
import com.attendance.audit.service.AttendanceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AttendanceApiController {

    private final AttendanceService attendanceService;

    public AttendanceApiController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/files")
    public List<String> files() {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        return attendanceService.listSourceFiles(baseDirectory).stream()
                .map(path -> path.getFileName().toString())
                .toList();
    }

    @GetMapping("/report")
    public ReportResponse report(@RequestParam(name = "file", required = false) String fileName) {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        Path sourceFile = attendanceService.resolveSourceFile(baseDirectory, fileName);
        AttendanceDataset dataset = attendanceService.loadDataset(sourceFile);
        List<SummaryRow> summaryRows = attendanceService.buildSummaryRows(dataset);
        List<DetailRowResponse> detailRows = attendanceService.buildDetailRows(dataset).stream()
                .map(row -> DetailRowResponse.from(row, attendanceService))
                .toList();
        List<EmployeeOverview> employees = dataset.employees().stream()
                .map(employee -> EmployeeOverview.from(employee, attendanceService))
                .toList();

        DailySummary dailySummary = attendanceService.buildDailySummary(dataset);
        return new ReportResponse(
                dataset.sourceFile().getFileName().toString(),
                dataset.startDate().toString(),
                dataset.endDate().toString(),
                attendanceService.buildSourceSheetPreviews(sourceFile),
                attendanceService.buildHeaderMetrics(dataset, summaryRows),
                summaryRows,
                employees,
                detailRows,
                dailySummary
        );
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        Path stored = attendanceService.storeUpload(baseDirectory, file.getOriginalFilename(), file.getInputStream());
        attendanceService.syncRulesFromFile(stored);
        return new UploadResponse(stored.getFileName().toString());
    }

    @DeleteMapping("/clear")
    public ClearResponse clear() {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        attendanceService.clearUploads(baseDirectory);
        return new ClearResponse(true);
    }

    @GetMapping("/employees")
    public List<EmployeeRule> employees() {
        return attendanceService.listRules();
    }

    @PostMapping("/employees/{employeeId}")
    public UpdateRuleResponse updateEmployeeRule(
            @PathVariable("employeeId") String employeeId,
            @RequestBody UpdateRuleRequest request) {
        attendanceService.updateRule(employeeId, request.dormitoryLunch(), request.flexibleLunch(), request.dinnerDeduct());
        return new UpdateRuleResponse(true);
    }

    public record UpdateRuleRequest(boolean dormitoryLunch, boolean flexibleLunch, boolean dinnerDeduct) {
    }

    public record UpdateRuleResponse(boolean success) {
    }

    public record UploadResponse(String fileName) {
    }

    public record ClearResponse(boolean success) {
    }

    public record ReportResponse(
            String sourceFileName,
            String startDate,
            String endDate,
            List<SourceSheetPreview> sourceSheets,
            Map<String, Object> metrics,
            List<SummaryRow> summaryRows,
            List<EmployeeOverview> employees,
            List<DetailRowResponse> detailRows,
            DailySummary dailySummary
    ) {
    }

    public record EmployeeOverview(
            String employeeId,
            String name,
            String department,
            boolean dormitoryLunch,
            boolean flexibleLunch,
            boolean dinnerDeduct,
            String lunchLabel,
            int totalMinutes,
            double totalHours,
            double totalUnits,
            long workedDays,
            long exceptionDays,
            List<DetailRowResponse> dayRows
    ) {
        static EmployeeOverview from(EmployeeRecord employee, AttendanceService attendanceService) {
            List<DetailRowResponse> rows = employee.days().stream()
                    .filter(day -> !day.rawText().isBlank() || !day.punches().isEmpty())
                    .map(day -> DetailRowResponse.from(
                            new DetailRow(employee.employeeId(), employee.name(), employee.department(), day),
                            attendanceService
                    ))
                    .toList();
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
            return new EmployeeOverview(
                    employee.employeeId(),
                    employee.name(),
                    employee.department(),
                    employee.dormitoryLunch(),
                    employee.flexibleLunch(),
                    employee.dinnerDeduct(),
                    lunchLabel,
                    employee.totalMinutes(),
                    Math.round((employee.totalMinutes() / 60.0) * 100.0) / 100.0,
                    employee.totalUnits(),
                    employee.workedDays(),
                    employee.exceptionDays(),
                    rows
            );
        }
    }

    public record DetailRowResponse(
            String employeeId,
            String name,
            String department,
            String workDate,
            String rawText,
            String punchesText,
            int punchCount,
            String lunchRule,
            int lunchDeductionMinutes,
            int dinnerDeductionMinutes,
            int durationMinutes,
            double durationHours,
            double workUnits,
            double morningUnits,
            double afternoonUnits,
            boolean overnightMerged,
            boolean incompletePunches,
            List<String> flags,
            String notes,
            String calculationBasis
    ) {
        static DetailRowResponse from(DetailRow row, AttendanceService attendanceService) {
            return new DetailRowResponse(
                    row.employeeId(),
                    row.name(),
                    row.department(),
                    row.dayRecord().workDate().toString(),
                    row.dayRecord().rawText(),
                    attendanceService.formatPunches(row.dayRecord().punches()),
                    row.dayRecord().punches().size(),
                    row.dayRecord().lunchRuleLabel(),
                    row.dayRecord().lunchDeductionMinutes(),
                    row.dayRecord().dinnerDeductionMinutes(),
                    row.dayRecord().durationMinutes(),
                    Math.round((row.dayRecord().durationMinutes() / 60.0) * 100.0) / 100.0,
                    row.dayRecord().workUnits(),
                    row.dayRecord().morningUnits(),
                    row.dayRecord().afternoonUnits(),
                    row.dayRecord().overnightMerged(),
                    row.dayRecord().incompletePunches(),
                    attendanceService.buildFlags(row.dayRecord()),
                    attendanceService.joinNotes(row.dayRecord().notes()),
                    row.dayRecord().calculationBasis()
            );
        }
    }

    public record SourceSheetPreview(
            String sheetName,
            int rowCount,
            int columnCount,
            List<List<String>> previewRows
    ) {
    }
}
