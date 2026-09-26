package ru.shtabklassa.service;

import org.springframework.stereotype.Service;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.model.*;
import ru.shtabklassa.repository.*;
import ru.shtabklassa.util.CsvWriter;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// в CSV весь класс, и молчащие тоже - отфильтровать в экселе проще, чем искать кого нет
@Service
public class ReportService {

    private static final DateTimeFormatter ANSWER_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> HEADER = List.of("Родитель", "Дети", "Статус", "Ответ", "Время ответа", "Доставка");

    public record Report(String fileName, byte[] content, String caption) {
    }

    private final AnnouncementRepository announcements;
    private final AnnouncementReadRepository reads;
    private final AnnouncementDeliveryRepository announcementDeliveries;
    private final PollRepository polls;
    private final PollAnswerRepository pollAnswers;
    private final PollDeliveryRepository pollDeliveries;
    private final UserRepository users;
    private final AnnouncementService announcementService;
    private final PollService pollService;
    private final MessageSender sender;
    private final Clock clock;

    // чтобы второй клик не прислал второй файл
    private final Set<SummaryTarget> inProgress = ConcurrentHashMap.newKeySet();

    public ReportService(AnnouncementRepository announcements, AnnouncementReadRepository reads,
                         AnnouncementDeliveryRepository announcementDeliveries, PollRepository polls,
                         PollAnswerRepository pollAnswers, PollDeliveryRepository pollDeliveries,
                         UserRepository users, AnnouncementService announcementService, PollService pollService,
                         MessageSender sender, Clock clock) {
        this.announcements = announcements;
        this.reads = reads;
        this.announcementDeliveries = announcementDeliveries;
        this.polls = polls;
        this.pollAnswers = pollAnswers;
        this.pollDeliveries = pollDeliveries;
        this.users = users;
        this.announcementService = announcementService;
        this.pollService = pollService;
        this.sender = sender;
        this.clock = clock;
    }

    // пока этот отчёт грузится - TooSoon. MessageDeliveryException если файл не ушёл
    public void send(String teacherExternalId, SummaryTarget target) {
        if (!inProgress.add(target)) {
            throw new DomainException.TooSoon("Отчёт уже готовится.");
        }
        try {
            Report report = build(teacherExternalId, target);
            sender.sendDocument(teacherExternalId, report.fileName(), report.content(), report.caption());
        } finally {
            inProgress.remove(target);
        }
    }

    public Report build(String teacherExternalId, SummaryTarget target) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Отчёт доступен классному руководителю.");
        }
        return switch (target.kind()) {
            case ANNOUNCEMENT -> announcementReport(teacher, target.id());
            case POLL -> pollReport(teacher, target.id());
        };
    }

    private Report announcementReport(User teacher, long id) {
        Announcement announcement = announcements.findById(id)
                .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
        requireSameClass(teacher, announcement.getClassId());

        var readByParent = reads.findByAnnouncementId(id).stream()
                .collect(Collectors.toMap(AnnouncementRead::getUserId, Function.identity()));
        Map<Long, String> deliveryByParent = announcementDeliveries.findByAnnouncementId(id).stream()
                .collect(Collectors.toMap(AnnouncementDelivery::getUserId, delivery -> deliveryStatus(delivery.getFailedReason())));

        List<Row> rows = new ArrayList<>();
        for (User parent : users.findByClassIdAndRoleOrderByFullName(announcement.getClassId(), Role.PARENT)) {
            AnnouncementRead read = readByParent.get(parent.getId());
            String answer = read == null ? null
                    : read.getDecision() == null ? "Прочитал"
                    : read.getDecision() == Decision.AGREE ? "Согласен" : "Не смогу";
            rows.add(new Row(parent, answer, read == null ? null : read.getReadAt(),
                    deliveryByParent.getOrDefault(parent.getId(), "Не отправлялось")));
        }

        AnnouncementSummary summary = announcementService.summarize(id);
        return new Report(fileName("announcement", id), toCsv(announcement.getClassId(), rows),
                caption(announcement.getText(), summary.answered(), summary.totalParents()));
    }

    private Report pollReport(User teacher, long id) {
        Poll poll = polls.findById(id).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
        requireSameClass(teacher, poll.getClassId());

        Map<Long, PollAnswer> answerByParent = pollAnswers.findByPollId(id).stream()
                .collect(Collectors.toMap(PollAnswer::getUserId, Function.identity()));
        Map<Long, String> deliveryByParent = pollDeliveries.findByPollId(id).stream()
                .collect(Collectors.toMap(PollDelivery::getUserId, delivery -> deliveryStatus(delivery.getFailedReason())));

        List<Row> rows = new ArrayList<>();
        for (User parent : users.findByClassIdAndRoleOrderByFullName(poll.getClassId(), Role.PARENT)) {
            PollAnswer answer = answerByParent.get(parent.getId());
            rows.add(new Row(parent, answer == null ? null : PollService.answerLabel(poll, answer.getAnswer()),
                    answer == null ? null : answer.getAnsweredAt(),
                    deliveryByParent.getOrDefault(parent.getId(), "Не отправлялось")));
        }

        PollSummary summary = pollService.summarize(id);
        return new Report(fileName("poll", id), toCsv(poll.getClassId(), rows),
                caption(poll.getQuestion(), summary.answered(), summary.totalParents()));
    }

    private record Row(User parent, String answer, Instant answeredAt, String delivery) {
        boolean answered() {
            return answer != null;
        }
    }

    private byte[] toCsv(long classId, List<Row> rows) {
        Map<Long, String> childrenByParent = users.findByClassIdAndRoleOrderByFullName(classId, Role.STUDENT).stream()
                .filter(student -> student.getParentId() != null)
                .collect(Collectors.groupingBy(User::getParentId,
                        Collectors.mapping(User::getFullName, Collectors.joining(", "))));

        CsvWriter csv = new CsvWriter().row(HEADER);
        // молчащие сверху, им звонить
        rows.stream()
                .sorted(Comparator.comparing(Row::answered))
                .forEach(row -> csv.row(
                        row.parent().getFullName(),
                        childrenByParent.get(row.parent().getId()),
                        row.answered() ? "Ответил" : "Не ответил",
                        row.answer(),
                        row.answeredAt() == null ? null : ANSWER_TIME.format(row.answeredAt().atZone(SchoolTime.ZONE)),
                        row.delivery()));
        return csv.toBytes();
    }

    // коды типа "901: Can't send" учителю ни о чём, а файл ещё и пересылают. подробности в логе
    private static String deliveryStatus(String failedReason) {
        if (failedReason == null) {
            return "Доставлено";
        }
        return ParentBroadcaster.DEMO_REASON.equals(failedReason) ? "Демо-аккаунт" : "Не доставлено";
    }

    private String fileName(String kind, long id) {
        return "report-" + kind + "-" + id + "-" + FILE_DATE.format(clock.instant().atZone(SchoolTime.ZONE)) + ".csv";
    }

    private static String caption(String title, long answered, long total) {
        String preview = title.length() <= 60 ? title : title.substring(0, 59) + "…";
        return "📥 Отчёт: " + preview + " · Ответили " + answered + " из " + total;
    }

    private static void requireSameClass(User teacher, Long classId) {
        if (!Objects.equals(teacher.getClassId(), classId)) {
            throw new DomainException.NotAllowed("Это не ваш класс.");
        }
    }
}
