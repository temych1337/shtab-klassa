package ru.shtabklassa.seed;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.shtabklassa.core.config.MaxProperties;
import ru.shtabklassa.core.config.VkProperties;
import ru.shtabklassa.model.*;
import ru.shtabklassa.repository.ClassRepository;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.service.*;
import ru.shtabklassa.util.SchoolTime;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/*
 * Демо-класс на 27 семей, чтобы сразу было "Ответили 21 из 27 · Согласны: 18 · Не смогут: 3".
 * ./gradlew bootRun --args='--spring.profiles.active=seed'
 *
 * Всё через обычные сервисы, как в бою. Заранее отвечают только demo-NN, телефоны из parent-ids всегда молчат -
 * им жать кнопки на показе. Класс уже есть -> ничего не делаем (если не seed.reset=true).
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    static final int MAX_REAL_PARENTS = 3;
    private static final Locale RU = Locale.forLanguageTag("ru");

    public record Seeded(long classId, long excursionId, long changeShoesId, long tripPollId, long giftPollId) {
    }

    private final SeedProperties props;
    private final VkProperties vk;
    private final MaxProperties max;
    private final ClassRepository classes;
    private final UserRepository users;
    private final AnnouncementService announcements;
    private final PollService polls;
    private final CalendarService calendar;
    private final DutyService duties;
    private final JdbcTemplate jdbc;
    private final Scheduler quartz;
    private final Clock clock;

    public SeedRunner(SeedProperties props, VkProperties vk, MaxProperties max, ClassRepository classes, UserRepository users,
                      AnnouncementService announcements, PollService polls, CalendarService calendar,
                      DutyService duties, JdbcTemplate jdbc, Scheduler quartz, Clock clock) {
        this.props = props;
        this.vk = vk;
        this.max = max;
        this.classes = classes;
        this.users = users;
        this.announcements = announcements;
        this.polls = polls;
        this.calendar = calendar;
        this.duties = duties;
        this.jdbc = jdbc;
        this.quartz = quartz;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(props.reset());
    }

    public Optional<Seeded> seed(boolean reset) {
        List<String> realParents = props.parentIds();
        if (realParents.size() > MAX_REAL_PARENTS) {
            throw new IllegalStateException("seed.parent-ids: не больше " + MAX_REAL_PARENTS + " телефонов для демо");
        }
        if ((vk.enabled() || max.enabled()) && props.teacherId().startsWith(ParentBroadcaster.DEMO_PREFIX)) {
            throw new IllegalStateException("мессенджер включён, а seed.teacher-id не задан, сводки учителю некуда слать");
        }

        if (reset) {
            wipeEverything();
        } else if (classes.findByName(DemoFamilies.CLASS_NAME).isPresent()) {
            log.info("демо-класс уже засеян, пропускаю (seed.reset=true чтобы засеять заново)");
            return Optional.empty();
        }

        ClassEntity klass = classes.save(new ClassEntity(DemoFamilies.CLASS_NAME));
        User teacher = users.save(new User(props.teacherId(), Role.TEACHER, klass.getId(), DemoFamilies.TEACHER));
        klass.setTeacherId(teacher.getId());
        classes.save(klass);

        List<User> parents = new ArrayList<>();
        for (int i = 0; i < DemoFamilies.ALL.size(); i++) {
            DemoFamilies.Family family = DemoFamilies.ALL.get(i);
            String externalId = i < realParents.size()
                    ? realParents.get(i)
                    : ParentBroadcaster.DEMO_PREFIX + String.format("%02d", i + 1);
            User parent = users.save(new User(externalId, Role.PARENT, klass.getId(), family.parent()));
            User child = new User(null, Role.STUDENT, klass.getId(), family.child());
            child.setParentId(parent.getId());
            users.save(child);
            parents.add(parent);
        }
        // настоящие телефоны жмут сами на показе
        List<User> demoParents = parents.subList(realParents.size(), parents.size());

        var random = new Random(2026);
        Seeded seeded = new Seeded(klass.getId(),
                seedExcursion(teacher, demoParents, random),
                seedChangeShoes(teacher, demoParents, random),
                seedTripPoll(teacher, demoParents, random),
                seedGiftPoll(teacher, demoParents, random));

        Instant meetingAt = SchoolTime.today(clock).plusDays(3).atTime(18, 30).atZone(SchoolTime.ZONE).toInstant();
        calendar.createEvent(teacher.getExternalId(), EventType.MEETING,
                new EventInput.Parsed(meetingAt, "Родительское собрание: итоги первой четверти"));
        duties.planTwoWeeks(teacher.getExternalId());

        // после backdate перерисовать, иначе "последние" в неправильном порядке
        announcements.pushSummary(seeded.excursionId());
        announcements.pushSummary(seeded.changeShoesId());
        polls.pushSummary(seeded.tripPollId());
        polls.pushSummary(seeded.giftPollId());

        log.info("демо-класс засеян: {} семей, из них настоящих телефонов {}", parents.size(), realParents.size());
        return Optional.of(seeded);
    }

    // главное: 21/27, 18 да, 3 нет
    private long seedExcursion(User teacher, List<User> demoParents, Random random) {
        LocalDate trip = SchoolTime.today(clock).plusDays(10);
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMMM", RU);
        long id = announcements.publish(teacher.getExternalId(),
                "Экскурсия в планетарий " + day.format(trip) + ". Выезд в 9:00 от школы, вернёмся к 14:00. "
                        + "Стоимость 450 ₽, сдать до " + day.format(trip.minusDays(2)) + ". Поедет ли ребёнок?",
                true).id();
        List<User> answered = demoParents.subList(0, 21);
        for (int i = 0; i < answered.size(); i++) {
            // вразброс, чтоб в отчёте не подряд
            Decision decision = i == 4 || i == 11 || i == 17 ? Decision.DECLINE : Decision.AGREE;
            announcements.mark(id, answered.get(i).getExternalId(), decision);
        }
        backdate("announcements", "announcement_reads", "read_at", "announcement_id", id, answered, random);
        return id;
    }

    // 24 из 27 прочитали
    private long seedChangeShoes(User teacher, List<User> demoParents, Random random) {
        long id = announcements.publish(teacher.getExternalId(),
                "С понедельника обязательно сменная обувь — на входе будут проверять.", false).id();
        List<User> read = demoParents.subList(0, 24);
        read.forEach(parent -> announcements.mark(id, parent.getExternalId(), null));
        backdate("announcements", "announcement_reads", "read_at", "announcement_id", id, read, random);
        return id;
    }

    private long seedTripPoll(User teacher, List<User> demoParents, Random random) {
        long id = polls.publish(teacher.getExternalId(), PollType.CHOICE, "Куда едем на экскурсию в мае?",
                List.of("Планетарий", "Зоопарк", "Музей космонавтики")).id();
        List<User> voted = demoParents.subList(0, 15);
        for (int i = 0; i < voted.size(); i++) {
            String option = i < 7 ? "0" : i < 12 ? "1" : "2";
            polls.answer(id, voted.get(i).getExternalId(), option);
        }
        backdate("polls", "poll_answers", "answered_at", "poll_id", id, voted, random);
        return id;
    }

    private long seedGiftPoll(User teacher, List<User> demoParents, Random random) {
        long id = polls.publish(teacher.getExternalId(), PollType.CUSTOM, "Идеи подарка учителям ко Дню учителя?",
                List.of()).id();
        List<String> ideas = List.of(
                "Общий букет и сертификат в книжный",
                "Фотокнига с рисунками детей",
                "Чайный набор от всего класса",
                "Мастер-класс по керамике для учителей");
        List<User> answered = demoParents.subList(0, ideas.size());
        for (int i = 0; i < ideas.size(); i++) {
            polls.answer(id, answered.get(i).getExternalId(), ideas.get(i));
        }
        backdate("polls", "poll_answers", "answered_at", "poll_id", id, answered, random);
        return id;
    }

    // размазать ответы по двум суткам, а то в отчёте все ответили в одну секунду
    private void backdate(String itemTable, String answerTable, String timeColumn, String itemColumn,
                          long itemId, List<User> answered, Random random) {
        Instant now = clock.instant();
        jdbc.update("UPDATE " + itemTable + " SET created_at = ? WHERE id = ?",
                Timestamp.from(now.minus(Duration.ofHours(49))), itemId);
        for (User parent : answered) {
            Instant at = now.minus(Duration.ofMinutes(5 + random.nextInt(48 * 60)));
            jdbc.update("UPDATE " + answerTable + " SET " + timeColumn + " = ? WHERE " + itemColumn + " = ? AND user_id = ?",
                    Timestamp.from(at), itemId, parent.getId());
        }
    }

    // сносит ВСЁ, не только демо-класс
    private void wipeEverything() {
        log.warn("seed.reset=true: удаляю все данные");
        try {
            quartz.clear();
        } catch (SchedulerException e) {
            throw new IllegalStateException("не удалось очистить задачи Quartz", e);
        }
        for (String table : List.of("calendar_reminder_log", "announcement_deliveries", "poll_deliveries",
                "announcement_reads", "announcements", "poll_answers", "poll_options", "polls",
                "calendar_event_reminder_offsets", "calendar_events", "duty_schedules")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE classes SET teacher_id = NULL");
        jdbc.update("UPDATE users SET parent_id = NULL");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM classes");
    }
}
