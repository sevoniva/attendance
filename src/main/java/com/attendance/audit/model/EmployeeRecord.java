package com.attendance.audit.model;

import java.util.List;

public record EmployeeRecord(
        String employeeId,
        String name,
        String department,
        boolean dormitoryLunch,
        boolean flexibleLunch,
        boolean dinnerDeduct,
        List<DayRecord> days
) {
    public int totalMinutes() {
        return days.stream().mapToInt(DayRecord::durationMinutes).sum();
    }

    public double totalUnits() {
        return days.stream().mapToDouble(DayRecord::workUnits).sum();
    }

    public long workedDays() {
        return days.stream().filter(day -> !day.punches().isEmpty()).count();
    }

    public long exceptionDays() {
        return days.stream().filter(day -> day.overnightMerged() || day.incompletePunches()).count();
    }
}
