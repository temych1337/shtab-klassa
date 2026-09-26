package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.time.LocalDate;

// один дежурный на класс в день, уникальный индекс в бд
@Entity
@Table(name = "duty_schedules")
public class DutySchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "duty_date", nullable = false)
    private LocalDate date;

    @Column(name = "rotation_order", nullable = false)
    private int rotationOrder;

    protected DutySchedule() {
    }

    public DutySchedule(Long classId, Long studentId, LocalDate date, int rotationOrder) {
        this.classId = classId;
        this.studentId = studentId;
        this.date = date;
        this.rotationOrder = rotationOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getRotationOrder() {
        return rotationOrder;
    }
}
