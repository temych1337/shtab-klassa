package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.shtabklassa.model.Announcement;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.AnnouncementRepository;
import ru.shtabklassa.repository.PollRepository;
import ru.shtabklassa.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/*
 * "Напомнить молчащим". Не чаще раза в shtab.reminder.cooldown: окно занимаем атомарным update ДО рассылки,
 * так что двойной клик = одна рассылка. Если не ушло никому - окно откатываем, а то учитель из-за сети
 * застрянет на 10 минут.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    public record ReminderResult(int reminded, int failed, int demo) {
        public boolean nobodySilent() {
            return reminded == 0 && failed == 0 && demo == 0;
        }

        static ReminderResult of(ParentBroadcaster.Tally tally) {
            return new ReminderResult(tally.delivered(), tally.failed(), tally.demo());
        }
    }

    // repeated - этот же список только что отправляли, второй раз не шлём
    public record SilentList(String title, List<String> names, boolean repeated) {

        public String render() {
            String preview = title.length() <= 50 ? title : title.substring(0, 49) + "…";
            return "👥 Не ответили (" + names.size() + ") — «" + preview + "»:\n" + String.join("\n", names);
        }
    }

    private record SentSilentList(Instant sentAt, List<String> names) {
    }

    private static final Duration SILENT_LIST_REPEAT_WINDOW = Duration.ofMinutes(1);
    private final Map<SummaryTarget, SentSilentList> lastSilentLists = new ConcurrentHashMap<>();

    private final AnnouncementRepository announcements;
    private final PollRepository polls;
    private final UserRepository users;
    private final AnnouncementService announcementService;
    private final PollService pollService;
    private final SummaryRefresher summaryRefresher;
    private final Clock clock;
    private final Duration cooldown;

    public ReminderService(AnnouncementRepository announcements, PollRepository polls, UserRepository users,
                           AnnouncementService announcementService, PollService pollService,
                           SummaryRefresher summaryRefresher, Clock clock,
                           @Value("${shtab.reminder.cooldown:10m}") Duration cooldown) {
        this.announcements = announcements;
        this.polls = polls;
        this.users = users;
        this.announcementService = announcementService;
        this.pollService = pollService;
        this.summaryRefresher = summaryRefresher;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    // TooSoon если напоминали меньше cooldown назад. молчащих нет -> окно не трогаем
    public ReminderResult remindSilent(String teacherExternalId, SummaryTarget target) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Напоминает только классный руководитель.");
        }
        // в бд микросекунды, без truncate releaseReminder не найдёт строку по равенству
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReminderResult result = switch (target.kind()) {
            case ANNOUNCEMENT -> remindAnnouncement(teacher, target.id(), now);
            case POLL -> remindPoll(teacher, target.id(), now);
        };
        if (!result.nobodySilent()) {
            log.info("напоминание {}: отправлено {}, не доставлено {}, демо {}", target, result.reminded(), result.failed(), result.demo());
            summaryRefresher.requestRefresh(target);
        }
        return result;
    }

    // только имена родителей, без детей и контактов. тот же список в течение минуты -> repeated
    public SilentList silentList(String teacherExternalId, SummaryTarget target) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Список доступен классному руководителю.");
        }
        String title;
        List<User> silent;
        switch (target.kind()) {
            case ANNOUNCEMENT -> {
                Announcement announcement = announcements.findById(target.id())
                        .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
                requireSameClass(teacher, announcement.getClassId());
                title = announcement.getText();
                silent = users.findSilentParentsForAnnouncement(announcement.getClassId(), target.id());
            }
            case POLL -> {
                Poll poll = polls.findById(target.id()).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
                requireSameClass(teacher, poll.getClassId());
                title = poll.getQuestion();
                silent = users.findSilentParentsForPoll(poll.getClassId(), target.id());
            }
            default -> throw new IllegalStateException(target.kind().name());
        }
        List<String> names = silent.stream().map(User::getFullName).toList();
        if (names.isEmpty()) {
            return new SilentList(title, names, false);
        }

        Instant now = clock.instant();
        boolean[] repeated = {false};
        lastSilentLists.compute(target, (key, previous) -> {
            if (previous != null && previous.names().equals(names)
                    && Duration.between(previous.sentAt(), now).compareTo(SILENT_LIST_REPEAT_WINDOW) < 0) {
                repeated[0] = true;
                return previous;
            }
            return new SentSilentList(now, names);
        });
        return new SilentList(title, names, repeated[0]);
    }

    private ReminderResult remindAnnouncement(User teacher, long id, Instant now) {
        Announcement announcement = announcements.findById(id)
                .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
        requireSameClass(teacher, announcement.getClassId());

        List<User> silent = users.findSilentParentsForAnnouncement(announcement.getClassId(), id);
        if (silent.isEmpty()) {
            return new ReminderResult(0, 0, 0);
        }
        if (announcements.claimReminder(id, now, now.minus(cooldown)) == 0) {
            throw tooSoon(announcements.findById(id).orElseThrow().getLastRemindedAt(), now);
        }
        User author = users.findById(announcement.getAuthorId()).orElseThrow();
        ParentBroadcaster.Tally tally = announcementService.deliver(announcement, silent,
                "🔔 Напоминание\n\n" + announcementService.messageFor(announcement, author));
        if (tally.delivered() == 0) {
            announcements.releaseReminder(id, now, announcement.getLastRemindedAt());
        }
        return ReminderResult.of(tally);
    }

    private ReminderResult remindPoll(User teacher, long id, Instant now) {
        Poll poll = polls.findById(id).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
        requireSameClass(teacher, poll.getClassId());
        if (poll.isClosed()) {
            throw new DomainException.InvalidInput("Опрос закрыт.");
        }

        List<User> silent = users.findSilentParentsForPoll(poll.getClassId(), id);
        if (silent.isEmpty()) {
            return new ReminderResult(0, 0, 0);
        }
        if (polls.claimReminder(id, now, now.minus(cooldown)) == 0) {
            throw tooSoon(polls.findById(id).orElseThrow().getLastRemindedAt(), now);
        }
        User author = users.findById(poll.getAuthorId()).orElseThrow();
        ParentBroadcaster.Tally tally = pollService.deliver(poll, silent, "🔔 Напоминание\n\n" + pollService.messageFor(poll, author));
        if (tally.delivered() == 0) {
            polls.releaseReminder(id, now, poll.getLastRemindedAt());
        }
        return ReminderResult.of(tally);
    }

    private static void requireSameClass(User teacher, Long classId) {
        if (!Objects.equals(teacher.getClassId(), classId)) {
            throw new DomainException.NotAllowed("Это не ваш класс.");
        }
    }

    private DomainException.TooSoon tooSoon(Instant lastRemindedAt, Instant now) {
        long agoMinutes = Duration.between(lastRemindedAt, now).toMinutes();
        long waitMinutes = Math.max(1, (long) Math.ceil(
                (cooldown.toMillis() - Duration.between(lastRemindedAt, now).toMillis()) / 60_000.0));
        String ago = agoMinutes < 1 ? "только что" : agoMinutes + " мин назад";
        return new DomainException.TooSoon("Уже напомнили " + ago + ". Повторно — через " + waitMinutes + " мин.");
    }
}
