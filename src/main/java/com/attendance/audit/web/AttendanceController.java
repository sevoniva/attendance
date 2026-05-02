package com.attendance.audit.web;

import com.attendance.audit.model.AttendanceDataset;
import com.attendance.audit.service.AttendanceService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Controller
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/app/index.html";
    }

    @GetMapping("/export.csv")
    public ResponseEntity<ByteArrayResource> exportCsv(@RequestParam(name = "file", required = false) String fileName) {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        AttendanceDataset dataset = attendanceService.loadDataset(attendanceService.resolveSourceFile(baseDirectory, fileName));
        byte[] bytes = attendanceService.exportCsv(dataset).getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        String exportFileName = dataset.sourceFile().getFileName().toString().replaceFirst("\\.[^.]+$", "") + "_detail.csv";
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(exportFileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(bytes.length)
                .body(resource);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<ByteArrayResource> exportExcel(@RequestParam(name = "file", required = false) String fileName) {
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        AttendanceDataset dataset = attendanceService.loadDataset(attendanceService.resolveSourceFile(baseDirectory, fileName));
        byte[] bytes = attendanceService.exportExcel(dataset);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        String exportFileName = dataset.sourceFile().getFileName().toString().replaceFirst("\\.[^.]+$", "") + "_report.xlsx";
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(exportFileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(resource);
    }
}
