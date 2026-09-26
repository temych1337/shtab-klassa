package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.shtabklassa.model.DutySchedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DutyScheduleRepository extends JpaRepository<DutySchedule, Long> {

    // unique(class_id, duty_date) + on conflict = двойной клик не задвоит день
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO duty_schedules (class_id, student_id, duty_date, rotation_order)
            VALUES (:classId, :studentId, :date, :rotationOrder)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfDayFree(@Param("classId") long classId, @Param("studentId") long studentId,
                        @Param("date") LocalDate date, @Param("rotationOrder") int rotationOrder);

    List<DutySchedule> findByClassIdAndDateBetweenOrderByDate(Long classId, LocalDate from, LocalDate to);

    Optional<DutySchedule> findFirstByClassIdOrderByDateDesc(Long classId);

    List<DutySchedule> findByDateGreaterThanEqual(LocalDate from);
}
