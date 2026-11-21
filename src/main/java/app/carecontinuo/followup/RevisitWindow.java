package app.carecontinuo.followup;

import app.carecontinuo.common.Problems;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record RevisitWindow(LocalDate revisitFrom, LocalDate revisitTo, Long revisitDepartmentId) {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    boolean configured() {
        return revisitDepartmentId != null;
    }

    boolean matches(OffsetDateTime startsAt, long departmentId) {
        if (!configured()) return true;
        var date = startsAt.atZoneSameInstant(BEIJING).toLocalDate();
        return revisitDepartmentId == departmentId && !date.isBefore(revisitFrom) && !date.isAfter(revisitTo);
    }

    public void requireMatch(OffsetDateTime startsAt, long departmentId) {
        if (!matches(startsAt, departmentId)) throw Problems.conflict("所选时段不符合当前随访的复诊日期或科室。");
    }
}
