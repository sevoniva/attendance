package com.attendance.audit.model;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

public record AttendanceDataset(
        Path sourceFile,
        LocalDate startDate,
        LocalDate endDate,
        List<EmployeeRecord> employees
) {
}
