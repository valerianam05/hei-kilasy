package hei.school;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grade transcript of a student.
 */
@Component
public class GradeReport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public BigDecimal penalizedGrade(BigDecimal rawPoints, BigDecimal maxPoints, LocalDateTime submittedAt,
            LocalDate deadline) {
        if (maxPoints.signum() <= 0) {
            throw new IllegalArgumentException("Le barème doit être positif");
        }

        long lateDays = 0;
        if (deadline != null) {
            LocalDateTime deadlineAt = deadline.atTime(23, 59, 59);
            if (submittedAt.isAfter(deadlineAt)) {
                Duration delay = Duration.between(deadlineAt, submittedAt);
                lateDays = delay.toDays();
                if (!delay.minusDays(lateDays).isZero()) {
                    lateDays++;
                }
            }
        }

        long penalizedDays = Math.min(lateDays, 5);
        BigDecimal remainingRate = BigDecimal.ONE
                .subtract(new BigDecimal("0.10").multiply(BigDecimal.valueOf(penalizedDays)));
        BigDecimal grade = rawPoints.multiply(new BigDecimal("20"))
                .multiply(remainingRate)
                .divide(maxPoints, 1, RoundingMode.HALF_UP);
        return grade.max(BigDecimal.ZERO);
    }

    public Map<String, Object> build(long studentId) {
        List<Map<String, Object>> students = jdbcTemplate.queryForList("SELECT * FROM student WHERE id = ?", studentId);
        if (students.isEmpty()) {
            return null;
        }
        Map<String, Object> student = students.get(0);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT e.title, e.deadline, e.max_points, e.duration_minutes, "
                        + "s.id AS submission_id, s.submitted_at, s.raw_points, s.feedback, es.started_at "
                        + "FROM exercise e LEFT JOIN submission s ON s.exercise_id = e.id AND s.student_id = ? "
                        + "LEFT JOIN exercise_start es ON es.exercise_id = e.id AND es.student_id = ? "
                        + "ORDER BY e.deadline NULLS LAST",
                studentId, studentId);

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int graded = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            Date deadlineDate = (Date) row.get("deadline");
            LocalDate deadline = deadlineDate == null ? null : deadlineDate.toLocalDate();
            Integer durationMinutes = (Integer) row.get("duration_minutes");
            line.put("exercise", row.get("title"));
            line.put("deadline", deadline);

            Timestamp startTimestamp = (Timestamp) row.get("started_at");
            if (durationMinutes != null) {
                Long timeUsedMinutes = null;
                if (startTimestamp != null) {
                    LocalDateTime startedAt = startTimestamp.toLocalDateTime();
                    Timestamp submissionTimestamp = (Timestamp) row.get("submitted_at");
                    LocalDateTime endedAt = submissionTimestamp == null
                            ? LocalDateTime.now()
                            : submissionTimestamp.toLocalDateTime();
                    Duration used = Duration.between(startedAt, endedAt);
                    timeUsedMinutes = used.toMinutes();
                    if (!used.minusMinutes(timeUsedMinutes).isZero()) {
                        timeUsedMinutes++;
                    }
                }
                line.put("timeUsedMinutes", timeUsedMinutes);
            }

            if (row.get("submission_id") == null) {
                boolean deadlinePassed = deadline != null && deadline.isBefore(LocalDate.now());
                boolean timerExpired = durationMinutes != null && startTimestamp != null
                        && LocalDateTime.now().isAfter(startTimestamp.toLocalDateTime().plusMinutes(durationMinutes));
                if (deadlinePassed || timerExpired) {
                    line.put("status", "NON RENDU");
                } else {
                    line.put("status", "À RENDRE");
                }
                line.put("grade", null);
            } else {
                LocalDateTime submittedAt = ((Timestamp) row.get("submitted_at")).toLocalDateTime();
                line.put("submittedAt", submittedAt);
                line.put("late", deadline != null && submittedAt.isAfter(deadline.atTime(23, 59, 59)));
                if (row.get("raw_points") == null) {
                    line.put("status", "EN ATTENTE DE CORRECTION");
                    line.put("grade", null);
                } else {
                    BigDecimal raw = (BigDecimal) row.get("raw_points");
                    BigDecimal max = (BigDecimal) row.get("max_points");
                    BigDecimal grade = penalizedGrade(raw, max, submittedAt, deadline);
                    line.put("status", "CORRIGÉ");
                    line.put("grade", grade);
                    line.put("feedback", row.get("feedback"));
                    total = total.add(grade);
                    graded++;
                }
            }
            lines.add(line);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reference", student.get("reference"));
        report.put("student", student.get("full_name"));
        report.put("email", student.get("email"));
        report.put("cohort", student.get("cohort"));
        report.put("exercises", lines);
        report.put("average", graded == 0 ? null : total.divide(new BigDecimal(graded), 2, RoundingMode.HALF_UP));
        return report;
    }
}
