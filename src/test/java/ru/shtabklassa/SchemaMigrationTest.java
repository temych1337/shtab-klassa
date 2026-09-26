package ru.shtabklassa;

import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import ru.shtabklassa.model.*;
import ru.shtabklassa.repository.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// схему делает только flyway, hibernate на validate - разъехались сущность и миграция = контекст не поднимется.
// база своя: на общей count() видел хвосты от других тестов и тест моргал в зависимости от порядка
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:schema-only;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
class SchemaMigrationTest {

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Autowired ClassRepository classes;
    @Autowired UserRepository users;
    @Autowired AnnouncementRepository announcements;
    @Autowired AnnouncementReadRepository reads;
    @Autowired PollRepository polls;
    @Autowired PollAnswerRepository pollAnswers;
    @Autowired CalendarEventRepository events;
    @Autowired DutyScheduleRepository duties;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private ClassEntity klass;
    private User teacher;
    private User parent;

    @BeforeEach
    void seedMinimalClass() {
        klass = classes.save(new ClassEntity("5Б"));
        teacher = users.save(new User("vk-1", Role.TEACHER, klass.getId(), "Анна Сергеевна Козлова"));
        klass.setTeacherId(teacher.getId());
        parent = users.save(new User("vk-2", Role.PARENT, klass.getId(), "Ирина Петрова"));
        em.flush();
    }

    @Test
    void migrationsAppliedOnCleanDatabase() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void classAndTeacherReferenceEachOther() {
        em.clear();
        ClassEntity reloaded = classes.findById(klass.getId()).orElseThrow();
        assertThat(reloaded.getTeacherId()).isEqualTo(teacher.getId());
        assertThat(users.findById(teacher.getId()).orElseThrow().getClassId()).isEqualTo(klass.getId());
    }

    @Test
    void studentLinksToParentAndMayHaveNoExternalId() {
        User student = new User(null, Role.STUDENT, klass.getId(), "Миша Петров");
        student.setParentId(parent.getId());
        users.save(student);
        users.save(new User(null, Role.STUDENT, klass.getId(), "Соня Петрова"));
        em.flush();
        em.clear();

        User reloaded = users.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getParentId()).isEqualTo(parent.getId());
        assertThat(reloaded.getExternalId()).isNull();
    }

    @Test
    void sameExternalIdTwiceIsRejected() {
        assertThatThrownBy(() -> users.saveAndFlush(new User("vk-2", Role.PARENT, klass.getId(), "Дубль")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void announcementReadKeepsDecisionAndRoundTrips() {
        Announcement announcement = announcements.save(
                new Announcement(klass.getId(), teacher.getId(), "Экскурсия в пятницу, 500 ₽", true, now));
        AnnouncementRead read = new AnnouncementRead(announcement.getId(), parent.getId(), now);
        read.setDecision(Decision.DECLINE);
        reads.save(read);
        em.flush();
        em.clear();

        AnnouncementRead reloaded = reads.findById(new AnnouncementRead.Key(announcement.getId(), parent.getId()))
                .orElseThrow();
        assertThat(reloaded.getDecision()).isEqualTo(Decision.DECLINE);
        assertThat(reloaded.getReadAt()).isEqualTo(now);
        assertThat(announcements.findById(announcement.getId()).orElseThrow().isRequiresDecision()).isTrue();
    }

    @Test
    void secondReadOfSameAnnouncementDoesNotCreateSecondRow() {
        Announcement announcement = announcements.save(
                new Announcement(klass.getId(), teacher.getId(), "Родительское собрание", false, now));
        reads.saveAndFlush(new AnnouncementRead(announcement.getId(), parent.getId(), now));
        em.clear();
        reads.saveAndFlush(new AnnouncementRead(announcement.getId(), parent.getId(), now.plusSeconds(1)));

        assertThat(reads.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsDuplicateReadEvenBypassingJpa() {
        Announcement announcement = announcements.saveAndFlush(
                new Announcement(klass.getId(), teacher.getId(), "Сменка", false, now));
        String insert = "INSERT INTO announcement_reads (announcement_id, user_id, read_at) VALUES (?, ?, CURRENT_TIMESTAMP)";
        jdbc.update(insert, announcement.getId(), parent.getId());

        assertThatThrownBy(() -> jdbc.update(insert, announcement.getId(), parent.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownDecisionValueIsRejected() {
        Announcement announcement = announcements.saveAndFlush(
                new Announcement(klass.getId(), teacher.getId(), "Сбор на подарок", true, now));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO announcement_reads (announcement_id, user_id, read_at, decision) VALUES (?, ?, CURRENT_TIMESTAMP, 'MAYBE')",
                announcement.getId(), parent.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pollOptionsKeepButtonOrder() {
        Poll poll = polls.save(new Poll(PollType.CHOICE, "Куда едем?",
                List.of("Планетарий", "Зоопарк", "Музей"), klass.getId(), teacher.getId(), now));
        em.flush();
        em.clear();

        assertThat(polls.findById(poll.getId()).orElseThrow().getOptions())
                .containsExactly("Планетарий", "Зоопарк", "Музей");
    }

    @Test
    void changedPollAnswerOverwritesInsteadOfDuplicating() {
        Poll poll = polls.save(new Poll(PollType.CONSENT, "Согласны на фото?", List.of(), klass.getId(), teacher.getId(), now));
        pollAnswers.saveAndFlush(new PollAnswer(poll.getId(), parent.getId(), "YES", now));
        em.clear();

        PollAnswer answer = pollAnswers.findById(new PollAnswer.Key(poll.getId(), parent.getId())).orElseThrow();
        answer.changeAnswer("NO", now.plusSeconds(5));
        pollAnswers.saveAndFlush(answer);
        em.clear();

        assertThat(pollAnswers.count()).isEqualTo(1);
        assertThat(pollAnswers.findAll().getFirst().getAnswer()).isEqualTo("NO");
    }

    @Test
    void calendarEventStoresReminderOffsets() {
        CalendarEvent event = events.save(new CalendarEvent(klass.getId(), EventType.MEETING,
                "Родительское собрание", now.plus(3, ChronoUnit.DAYS), Set.of(1440, 60)));
        em.flush();
        em.clear();

        assertThat(events.findById(event.getId()).orElseThrow().getReminderOffsets())
                .containsExactlyInAnyOrder(1440, 60);
    }

    @Test
    void twoDutiesOnSameDateInOneClassAreRejected() {
        User first = users.save(new User(null, Role.STUDENT, klass.getId(), "Миша Петров"));
        User second = users.save(new User(null, Role.STUDENT, klass.getId(), "Катя Смирнова"));
        LocalDate monday = LocalDate.of(2026, 9, 28);
        duties.saveAndFlush(new DutySchedule(klass.getId(), first.getId(), monday, 1));

        assertThatThrownBy(() -> duties.saveAndFlush(new DutySchedule(klass.getId(), second.getId(), monday, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
