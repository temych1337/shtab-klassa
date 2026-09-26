package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.model.*;
import ru.shtabklassa.repository.AnnouncementDeliveryRepository;
import ru.shtabklassa.repository.AnnouncementReadRepository;
import ru.shtabklassa.repository.AnnouncementRepository;
import ru.shtabklassa.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    // у MAX лимит 4000, а к тексту ещё имя учителя и "Напоминание" приклеиваются
    static final int MAX_TEXT_LENGTH = 3500;

    private final AnnouncementRepository announcements;
    private final AnnouncementReadRepository reads;
    private final AnnouncementDeliveryRepository deliveries;
    private final UserRepository users;
    private final MessageSender sender;
    private final ParentBroadcaster broadcaster;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AnnouncementService(AnnouncementRepository announcements, AnnouncementReadRepository reads,
                               AnnouncementDeliveryRepository deliveries, UserRepository users,
                               MessageSender sender, ParentBroadcaster broadcaster,
                               ApplicationEventPublisher events, Clock clock) {
        this.announcements = announcements;
        this.reads = reads;
        this.deliveries = deliveries;
        this.users = users;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.events = events;
        this.clock = clock;
    }

    // без @Transactional специально: внутри http-запросы, коннект к бд на них держать нельзя.
    // сводку учителю шлём ДО рассылки, иначе первые клики родителей нечего будет править.
    // не ушла сводка -> MessageDeliveryException, объявление лежит неразосланным, учитель жмёт ещё раз
    public PublishResult publish(String teacherExternalId, String text, boolean requiresDecision) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Объявления рассылает только классный руководитель.");
        }
        if (teacher.getClassId() == null) {
            throw new DomainException.NotAllowed("Вы не привязаны к классу.");
        }
        String trimmed = validateText(text);

        Announcement announcement = announcements.save(
                new Announcement(teacher.getClassId(), teacher.getId(), trimmed, requiresDecision, clock.instant()));

        AnnouncementSummary initial = summarize(announcement.getId());
        MessageRef summaryMessage = sender.sendKeyboard(teacherExternalId, initial.render(),
                Keyboards.teacherSummary(SummaryTarget.announcement(announcement.getId()), initial.silent()));
        announcement.setSummaryMessageId(summaryMessage.messageId());
        announcement = announcements.save(announcement);

        List<User> parents = users.findByClassIdAndRoleOrderByFullName(teacher.getClassId(), Role.PARENT);
        ParentBroadcaster.Tally tally = deliver(announcement, parents, messageFor(announcement, teacher));
        if (tally.failed() > 0) {
            log.warn("объявление {}: не доставлено {} из {}", announcement.getId(), tally.failed(), parents.size());
        }
        if (tally.failed() > 0 || tally.demo() > 0) {
            // рассылка уже ушла! кинем тут - учитель нажмёт Отправить ещё раз и все получат дубль
            try {
                pushSummary(announcement.getId());
            } catch (MessageDeliveryException e) {
                log.warn("объявление {}: разослано, но сводка не обновилась: {}", announcement.getId(), e.getMessage());
            }
        }
        return PublishResult.of(announcement.getId(), tally);
    }

    // зовётся ещё на шаге черновика, чтобы ошибка была сразу, а не на "Отправить"
    public static String validateText(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException.InvalidInput("Текст объявления пустой.");
        }
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new DomainException.InvalidInput("Слишком длинно: максимум " + MAX_TEXT_LENGTH + " символов.");
        }
        return trimmed;
    }

    String messageFor(Announcement announcement, User author) {
        return "📢 " + author.getFullName() + ":\n\n" + announcement.getText();
    }

    // как объявление выглядит у родителя после нажатия. после "Прочитал" кнопку убираем,
    // с решением оставляем - можно передумать
    public ParentCard parentCard(long announcementId, String parentExternalId) {
        Announcement announcement = announcements.findById(announcementId)
                .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
        User parent = users.findByExternalId(parentExternalId).orElseThrow(DomainException.UnknownUser::new);
        User author = users.findById(announcement.getAuthorId()).orElseThrow();
        String text = messageFor(announcement, author);
        Keyboard buttons = Keyboards.forAnnouncement(announcementId, announcement.isRequiresDecision());

        AnnouncementRead read = reads.findById(new AnnouncementRead.Key(announcementId, parent.getId())).orElse(null);
        if (read == null) {
            return new ParentCard(text, buttons);
        }
        if (read.getDecision() == null) {
            return new ParentCard(text + "\n\n✓ Отмечено: прочитал", null);
        }
        return new ParentCard(text + "\n\n✓ Ваш ответ: " + (read.getDecision() == Decision.AGREE ? "согласен" : "не смогу"),
                buttons);
    }

    // напоминание перезаписывает строку доставки, так что дошедшие со второго раза уходят из "не доставлено"
    ParentBroadcaster.Tally deliver(Announcement announcement, List<User> recipients, String text) {
        Instant now = clock.instant();
        var outcomes = broadcaster.send(recipients, text,
                Keyboards.forAnnouncement(announcement.getId(), announcement.isRequiresDecision()));
        List<AnnouncementDelivery> rows = outcomes.stream()
                .map(outcome -> outcome.delivered()
                        ? AnnouncementDelivery.delivered(announcement.getId(), outcome.recipient().getId(), outcome.messageId(), now)
                        : AnnouncementDelivery.failed(announcement.getId(), outcome.recipient().getId(), outcome.failedReason(), now))
                .toList();
        deliveries.saveAll(rows);
        return ParentBroadcaster.Tally.of(outcomes);
    }

    /**
     * decision = null для "Прочитал". Повторный клик -> UNCHANGED, одновременные не дублируются.
     * InvalidInput если кнопка от старой версии объявления.
     */
    @Transactional
    public MarkOutcome mark(long announcementId, String parentExternalId, Decision decision) {
        User parent = users.findByExternalId(parentExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (parent.getRole() != Role.PARENT) {
            throw new DomainException.NotAllowed("Отмечаться могут только родители.");
        }
        Announcement announcement = announcements.findById(announcementId)
                .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
        if (!Objects.equals(announcement.getClassId(), parent.getClassId())) {
            throw new DomainException.NotAllowed("Это объявление другого класса.");
        }
        if (announcement.isRequiresDecision() != (decision != null)) {
            throw new DomainException.InvalidInput("Кнопка устарела.");
        }

        String decisionValue = decision == null ? null : decision.name();
        MarkOutcome outcome;
        if (reads.insertIfAbsent(announcementId, parent.getId(), clock.instant(), decisionValue) == 1) {
            outcome = MarkOutcome.RECORDED;
        } else if (reads.updateDecisionIfChanged(announcementId, parent.getId(), decisionValue) == 1) {
            outcome = MarkOutcome.CHANGED;
        } else {
            outcome = MarkOutcome.UNCHANGED;
        }

        if (outcome != MarkOutcome.UNCHANGED) {
            events.publishEvent(new AnswersChanged(SummaryTarget.announcement(announcementId)));
        }
        return outcome;
    }

    // всегда из бд, без счётчиков в памяти - так не разъедется
    public AnnouncementSummary summarize(long announcementId) {
        Announcement announcement = announcements.findById(announcementId)
                .orElseThrow(() -> new DomainException.NotFound("Объявление не найдено."));
        long totalParents = users.countByClassIdAndRole(announcement.getClassId(), Role.PARENT);
        long agreed = reads.countByAnnouncementIdAndDecision(announcementId, Decision.AGREE);
        long declined = reads.countByAnnouncementIdAndDecision(announcementId, Decision.DECLINE);
        long answered = announcement.isRequiresDecision() ? agreed + declined : reads.countByAnnouncementId(announcementId);
        long demo = deliveries.countByAnnouncementIdAndFailedReason(announcementId, ParentBroadcaster.DEMO_REASON);
        long undelivered = deliveries.countByAnnouncementIdAndFailedReasonIsNotNull(announcementId) - demo;
        return new AnnouncementSummary(announcementId, announcement.getText(), announcement.isRequiresDecision(),
                totalParents, answered, agreed, declined, undelivered, demo);
    }

    public void pushSummary(long announcementId) {
        Announcement announcement = announcements.findById(announcementId).orElse(null);
        if (announcement == null || announcement.getSummaryMessageId() == null) {
            return;
        }
        User author = users.findById(announcement.getAuthorId()).orElseThrow();
        AnnouncementSummary summary = summarize(announcementId);
        sender.editMessage(new MessageRef(author.getExternalId(), announcement.getSummaryMessageId()),
                summary.render(), Keyboards.teacherSummary(SummaryTarget.announcement(announcementId), summary.silent()));
    }
}
