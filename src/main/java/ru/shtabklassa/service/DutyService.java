package ru.shtabklassa.service;

import org.springframework.stereotype.Service;
import ru.shtabklassa.model.DutySchedule;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.util.PersonName;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// дежурства только в duty_schedules, EventType.DUTY не используем. праздники не учитываются, просто пн-пт
@Service
public class DutyService {

    static final int PLAN_DAYS = 14;

    public record PlanResult(int created, int totalInWindow) {
    }

    private final DutyScheduleRepository duties;
    private final UserRepository users;
    private final CalendarReminderScheduler reminders;
    private final Clock clock;

    public DutyService(DutyScheduleRepository duties, UserRepository users, CalendarReminderScheduler reminders,
                       Clock clock) {
        this.duties = duties;
        this.users = users;
        this.reminders = reminders;
        this.clock = clock;
    }

    // будни на 2 недели с завтра. занятые дни не трогаем, круг по алфавиту с того, кто дежурил последним
    public PlanResult planTwoWeeks(String teacherExternalId) {
        User teacher = requireTeacher(teacherExternalId);
        long classId = teacher.getClassId();
        var rotation = users.findByClassIdAndRoleOrderByFullName(classId, Role.STUDENT);
        if (rotation.isEmpty()) {
            throw new DomainException.InvalidInput("В классе нет учеников — некого ставить дежурить.");
        }

        LocalDate from = SchoolTime.today(clock).plusDays(1);
        LocalDate to = from.plusDays(PLAN_DAYS - 1);
        Set<LocalDate> taken = duties.findByClassIdAndDateBetweenOrderByDate(classId, from, to).stream()
                .map(DutySchedule::getDate)
                .collect(Collectors.toSet());

        int next = duties.findFirstByClassIdOrderByDateDesc(classId)
                .map(last -> indexOf(rotation, last.getStudentId()) + 1)
                .orElse(0);
        int created = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!SchoolTime.isSchoolDay(date) || taken.contains(date)) {
                continue;
            }
            int position = next % rotation.size();
            created += duties.insertIfDayFree(classId, rotation.get(position).getId(), date, position);
            next = position + 1;
        }

        List<DutySchedule> window = duties.findByClassIdAndDateBetweenOrderByDate(classId, from, to);
        window.forEach(reminders::scheduleDuty);
        return new PlanResult(created, window.size());
    }

    // последнего дежурного могли удалить из класса - тогда -1 и круг с начала
    private static int indexOf(List<User> rotation, Long studentId) {
        for (int i = 0; i < rotation.size(); i++) {
            if (rotation.get(i).getId().equals(studentId)) {
                return i;
            }
        }
        return -1;
    }

    public String overview(String teacherExternalId) {
        User teacher = requireTeacher(teacherExternalId);
        LocalDate today = SchoolTime.today(clock);
        List<DutySchedule> upcoming = duties.findByClassIdAndDateBetweenOrderByDate(
                teacher.getClassId(), today, today.plusDays(PLAN_DAYS));
        if (upcoming.isEmpty()) {
            return "🧹 График дежурств пуст.";
        }
        Map<Long, String> names = namesOf(upcoming);
        var text = new StringBuilder("🧹 Дежурства:");
        upcoming.forEach(duty -> text.append("\n").append(SchoolTime.day(duty.getDate())).append(" — ")
                .append(names.getOrDefault(duty.getStudentId(), "?")));
        return text.toString();
    }

    // parentId == null -> учитель, видит всех. иначе только свои дети и только по имени,
    // кто из чужих детей когда дежурит - не их дело
    List<Agenda.Entry> agendaEntries(long classId, LocalDate from, LocalDate to, Long parentId) {
        List<DutySchedule> window = duties.findByClassIdAndDateBetweenOrderByDate(classId, from, to);
        Map<Long, User> students = users.findAllById(window.stream().map(DutySchedule::getStudentId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, student -> student));
        return window.stream()
                .filter(duty -> parentId == null || students.containsKey(duty.getStudentId())
                        && parentId.equals(students.get(duty.getStudentId()).getParentId()))
                .map(duty -> {
                    User student = students.get(duty.getStudentId());
                    String who = student == null ? "?"
                            : parentId == null ? student.getFullName() : PersonName.first(student.getFullName());
                    return new Agenda.Entry(SchoolTime.dutyStart(duty.getDate()),
                            "🧹 " + SchoolTime.dayAndTime(SchoolTime.dutyStart(duty.getDate())) + " — дежурит " + who);
                })
                .toList();
    }

    private Map<Long, String> namesOf(List<DutySchedule> schedule) {
        return users.findAllById(schedule.stream().map(DutySchedule::getStudentId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }

    private User requireTeacher(String teacherExternalId) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Дежурства составляет классный руководитель.");
        }
        if (teacher.getClassId() == null) {
            throw new DomainException.NotAllowed("Вы не привязаны к классу.");
        }
        return teacher;
    }
}
