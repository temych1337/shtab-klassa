package ru.shtabklassa.core.security;

/**
 * НЕ РЕАЛИЗОВАНО, на потом. Пока удаляем руками в бд.
 *
 * План: по запросу родителя или при выбытии - fullName -> "Ученик #id", externalId = null,
 * снести его duty_schedules. Что делать с ответами родителей (учителю они нужны для отчётов) - решать
 * вместе со сроком хранения.
 */
public interface StudentDataEraser {

    // IllegalArgumentException если не найден или не STUDENT
    void anonymize(long studentId);
}
