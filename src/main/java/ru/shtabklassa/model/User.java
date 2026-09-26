package ru.shtabklassa.model;

import jakarta.persistence.*;

// полей специально минимум, см. приватность в README
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // id в мессенджере, у учеников null
    @Column(name = "external_id", length = 64, unique = true)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    // только у STUDENT, туда шлём напоминания о дежурстве
    @Column(name = "parent_id")
    private Long parentId;

    protected User() {
    }

    public User(String externalId, Role role, Long classId, String fullName) {
        this.externalId = externalId;
        this.role = role;
        this.classId = classId;
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Role getRole() {
        return role;
    }

    public Long getClassId() {
        return classId;
    }

    public void setClassId(Long classId) {
        this.classId = classId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }
}
