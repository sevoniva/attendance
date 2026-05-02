package com.attendance.audit.model;

import java.util.List;

public record DailySummaryRow(
        String employeeId,
        String name,
        List<Double> dailyWorkUnits,
        double totalWorkUnits
) {
}
