package com.attendance.audit.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DayRecord(
        int day,
        LocalDate workDate,
        String rawText,
        List<LocalDateTime> punches,
        int durationMinutes,
        double workUnits,
        int lunchDeductionMinutes,
        int dinnerDeductionMinutes,
        String lunchRuleLabel,
        boolean overnightMerged,
        boolean incompletePunches,
        List<String> notes,
        String calculationBasis,
        int morningMinutes,
        int afternoonMinutes,
        double morningUnits,
        double afternoonUnits
) {
}
