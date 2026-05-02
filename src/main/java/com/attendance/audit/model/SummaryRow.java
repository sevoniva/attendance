package com.attendance.audit.model;

public record SummaryRow(
        String employeeId,
        String name,
        String department,
        String lunchLabel,
        long workedDays,
        long exceptionDays,
        int totalMinutes,
        double totalHours,
        double totalUnits
) {
}
