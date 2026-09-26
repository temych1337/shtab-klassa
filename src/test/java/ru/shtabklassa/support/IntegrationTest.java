package ru.shtabklassa.support;

import org.junit.jupiter.api.BeforeEach;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ru.shtabklassa.model.ClassEntity;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.ClassRepository;
import ru.shtabklassa.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;

// полный контекст на чистой H2 + FakeMessageSender
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTest.FakeTransport.class)
public abstract class IntegrationTest {

    @TestConfiguration
    public static class FakeTransport {
        @Bean
        @Primary
        FakeMessageSender fakeMessageSender() {
            return new FakeMessageSender();
        }

        @Bean
        @Primary
        ShiftableClock shiftableClock() {
            return new ShiftableClock();
        }
    }

    @Autowired protected FakeMessageSender sender;
    @Autowired protected ShiftableClock clock;
    @Autowired protected Scheduler quartz;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ClassRepository classes;
    @Autowired protected UserRepository users;

    @BeforeEach
    void cleanDatabase() {
        try {
            quartz.clear();
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
        for (String table : List.of("calendar_reminder_log", "announcement_deliveries", "poll_deliveries",
                "announcement_reads", "announcements",
                "poll_answers", "poll_options", "polls", "calendar_event_reminder_offsets", "calendar_events",
                "duty_schedules")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE classes SET teacher_id = NULL");
        jdbc.update("UPDATE users SET parent_id = NULL");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM classes");
        sender.reset();
        clock.reset();
    }

    protected record TestClass(ClassEntity klass, User teacher, List<User> parents) {
    }

    // i-й ученик -> i-й родитель, если есть
    protected List<User> addStudents(TestClass c, String... names) {
        List<User> students = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            User student = new User(null, Role.STUDENT, c.klass().getId(), names[i]);
            if (i < c.parents().size()) {
                student.setParentId(c.parents().get(i).getId());
            }
            students.add(users.save(student));
        }
        return students;
    }

    // учитель t-{prefix}, родители p-{prefix}-1..N
    protected TestClass createClass(String prefix, int parentCount) {
        ClassEntity klass = classes.save(new ClassEntity(prefix));
        User teacher = users.save(new User("t-" + prefix, Role.TEACHER, klass.getId(), "Анна Сергеевна"));
        klass.setTeacherId(teacher.getId());
        classes.save(klass);
        List<User> parents = new ArrayList<>();
        for (int i = 1; i <= parentCount; i++) {
            parents.add(users.save(new User("p-" + prefix + "-" + i, Role.PARENT, klass.getId(), "Родитель " + i)));
        }
        return new TestClass(klass, teacher, parents);
    }
}
