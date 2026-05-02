package com.attendance.audit.model;

import java.util.List;

public record DailySummary(
        List<String> dates,
        List<DailySummaryRow> rows
) {
}
