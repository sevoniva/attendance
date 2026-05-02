package com.attendance.audit.model;

public record DetailRow(
        String employeeId,
        String name,
        String department,
        DayRecord dayRecord
) {
}
