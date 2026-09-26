package ru.shtabklassa.bot.handler;

import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.bot.state.TeacherDraftStore;
import ru.shtabklassa.bot.state.TeacherDraftStore.Draft;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.*;
import ru.shtabklassa.util.PersonName;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.util.List;

// шаги с кнопками правят одно и то же сообщение, чтоб не засорять чат
@Component
public class TeacherDialogHandler {

    private final TeacherDraftStore drafts;
    private final AnnouncementService announcements;
    private final PollService polls;
    private final CalendarService calendarService;
    private final CalendarHandler calendar;
    private final MessageSender sender;
    private final Clock clock;

    public TeacherDialogHandler(TeacherDraftStore drafts, AnnouncementService announcements, PollService polls,
                                CalendarService calendarService, CalendarHandler calendar, MessageSender sender,
                                Clock clock) {
        this.drafts = drafts;
        this.announcements = announcements;
        this.polls = polls;
        this.calendarService = calendarService;
        this.calendar = calendar;
        this.sender = sender;
        this.clock = clock;
    }

    void onText(User teacher, IncomingEvent.TextMessage message) {
        String teacherId = teacher.getExternalId();
        String peer = message.peerId();
        Payload.Action menuAction = Payload.parse(message.payload()).map(Payload::a).orElse(null);

        if (menuAction == Payload.Action.NEW_ANN || Keyboards.NEW_ANNOUNCEMENT_LABEL.equals(message.text())) {
            drafts.startAnnouncement(teacherId);
            sender.sendKeyboard(peer, "Напишите текст объявления одним сообщением.", Keyboards.cancelDraft());
            return;
        }
        if (menuAction == Payload.Action.NEW_POLL || Keyboards.NEW_POLL_LABEL.equals(message.text())) {
            drafts.startPoll(teacherId);
            sender.sendKeyboard(peer, "Какой опрос?", Keyboards.choosePollType());
            return;
        }
        if (CalendarHandler.isMenuButton(message, Payload.Action.CAL_VIEW, Keyboards.CALENDAR_LABEL)) {
            calendar.showCalendar(teacher, peer);
            return;
        }
        if (CalendarHandler.isMenuButton(message, Payload.Action.DUTY_VIEW, Keyboards.DUTIES_LABEL)) {
            calendar.showDuties(teacher, peer);
            return;
        }

        Draft draft = drafts.get(teacherId).orElse(null);
        if (draft != null && draft.step() == TeacherDraftStore.Step.AWAITING_TEXT) {
            if (draft.kind() == TeacherDraftStore.Kind.EVENT) {
                acceptEvent(teacherId, peer, message.text());
            } else {
                acceptText(teacherId, peer, draft, message.text());
            }
            return;
        }
        if (draft != null && draft.step() == TeacherDraftStore.Step.AWAITING_OPTIONS) {
            acceptOptions(teacherId, peer, message.text());
            return;
        }
        sender.sendKeyboard(peer, PersonName.address(teacher.getFullName()) + ", что делаем? Объявление, опрос, "
                + "календарь или дежурства — кнопками внизу.", Keyboards.teacherMenu());
    }

    void dropDraft(User user) {
        drafts.cancel(user.getExternalId());
    }

    private void acceptEvent(String teacherId, String peer, String text) {
        EventInput.Parsed parsed;
        try {
            parsed = EventInput.parse(text, clock.instant());
        } catch (DomainException.InvalidInput e) {
            sender.sendKeyboard(peer, e.getMessage(), Keyboards.cancelDraft());
            return;
        }
        drafts.acceptEvent(teacherId, parsed.title(), parsed.startAt())
                .ifPresent(next -> sender.sendKeyboard(peer, preview(next), Keyboards.confirmDraft()));
    }

    private void acceptText(String teacherId, String peer, Draft draft, String text) {
        String checked;
        try {
            checked = draft.kind() == TeacherDraftStore.Kind.ANNOUNCEMENT
                    ? AnnouncementService.validateText(text)
                    : PollService.validateQuestion(text);
        } catch (DomainException.InvalidInput e) {
            sender.sendKeyboard(peer, e.getMessage() + " Пришлите ещё раз.", Keyboards.cancelDraft());
            return;
        }
        Draft next = drafts.acceptText(teacherId, checked).orElse(null);
        if (next == null) {
            return;
        }
        switch (next.step()) {
            case AWAITING_MODE -> sender.sendKeyboard(peer, "Нужен ответ от родителей?", Keyboards.chooseMode());
            case AWAITING_OPTIONS -> sender.sendKeyboard(peer,
                    "Пришлите варианты одним сообщением, каждый с новой строки (от 2 до 6).", Keyboards.cancelDraft());
            case CONFIRM -> sender.sendKeyboard(peer, preview(next), Keyboards.confirmDraft());
            default -> throw new IllegalStateException("после текста не бывает шага " + next.step());
        }
    }

    private void acceptOptions(String teacherId, String peer, String text) {
        List<String> options;
        try {
            options = PollService.parseOptions(text);
        } catch (DomainException.InvalidInput e) {
            sender.sendKeyboard(peer, e.getMessage() + " Пришлите ещё раз.", Keyboards.cancelDraft());
            return;
        }
        drafts.acceptOptions(teacherId, options)
                .ifPresent(next -> sender.sendKeyboard(peer, preview(next), Keyboards.confirmDraft()));
    }

    String onCallback(User teacher, IncomingEvent.ButtonCallback callback, Payload payload) {
        String teacherId = teacher.getExternalId();
        return switch (payload.a()) {
            case MODE_READ, MODE_DECISION -> {
                Draft draft = drafts.chooseMode(teacherId, payload.a() == Payload.Action.MODE_DECISION).orElse(null);
                if (draft == null) {
                    yield "Черновик уже не актуален.";
                }
                show(callback, preview(draft), Keyboards.confirmDraft());
                yield null;
            }
            case PTYPE_CONSENT, PTYPE_CHOICE, PTYPE_CUSTOM -> {
                PollType type = switch (payload.a()) {
                    case PTYPE_CONSENT -> PollType.CONSENT;
                    case PTYPE_CHOICE -> PollType.CHOICE;
                    default -> PollType.CUSTOM;
                };
                if (drafts.choosePollType(teacherId, type).isEmpty()) {
                    yield "Черновик уже не актуален.";
                }
                show(callback, "Напишите вопрос одним сообщением.", Keyboards.cancelDraft());
                yield null;
            }
            case EVT_NEW -> {
                drafts.startEvent(teacherId);
                sender.sendKeyboard(callback.callback().peerId(), "Что за событие?", Keyboards.chooseEventType());
                yield null;
            }
            case ETYPE_MEETING, ETYPE_EVENT -> {
                EventType type = payload.a() == Payload.Action.ETYPE_MEETING ? EventType.MEETING : EventType.EVENT;
                if (drafts.chooseEventType(teacherId, type).isEmpty()) {
                    yield "Черновик уже не актуален.";
                }
                show(callback, "Дата, время (по Москве) и название одной строкой.\nПример: " + EventInput.EXAMPLE,
                        Keyboards.cancelDraft());
                yield null;
            }
            case DRAFT_SEND -> send(teacher, callback);
            case DRAFT_CANCEL -> {
                drafts.cancel(teacherId);
                show(callback, "Отменено.", null);
                yield null;
            }
            default -> "Кнопка устарела.";
        };
    }

    private String preview(Draft draft) {
        if (draft.kind() == TeacherDraftStore.Kind.EVENT) {
            return "Создать событие?\n\n" + CalendarService.icon(draft.eventType()) + " "
                    + SchoolTime.dayAndTime(draft.startAt()) + " — " + draft.text()
                    + "\n\nРодители получат уведомление сейчас и напоминания за день и за час.";
        }
        StringBuilder text = new StringBuilder("Отправить родителям?\n\n");
        if (draft.kind() == TeacherDraftStore.Kind.ANNOUNCEMENT) {
            text.append(draft.text()).append("\n\nОтвет: ")
                    .append(draft.requiresDecision() ? "«Согласен / Не смогу»" : "«Прочитал»");
            return text.toString();
        }
        text.append("📋 ").append(draft.text()).append("\n\n");
        switch (draft.pollType()) {
            case CONSENT -> text.append("Ответ: «Да / Нет»");
            case CHOICE -> draft.options().forEach(option -> text.append("• ").append(option).append("\n"));
            case CUSTOM -> text.append("Ответ: свободный текст");
        }
        return text.toString().strip();
    }

    private String send(User teacher, IncomingEvent.ButtonCallback callback) {
        Draft draft = drafts.takeConfirmed(teacher.getExternalId()).orElse(null);
        if (draft == null) {
            return "Уже отправлено.";
        }
        PublishResult result;
        try {
            result = switch (draft.kind()) {
                case ANNOUNCEMENT -> announcements.publish(teacher.getExternalId(), draft.text(), draft.requiresDecision());
                case POLL -> polls.publish(teacher.getExternalId(), draft.pollType(), draft.text(), draft.options());
                case EVENT -> {
                    CalendarService.Created created = calendarService.createEvent(teacher.getExternalId(),
                            draft.eventType(), new EventInput.Parsed(draft.startAt(), draft.text()));
                    ParentBroadcaster.Tally tally = created.notified();
                    yield new PublishResult(created.event().getId(), tally.delivered(), tally.failed(), tally.demo());
                }
            };
        } catch (RuntimeException e) {
            drafts.restore(teacher.getExternalId(), draft);
            throw e;
        }
        show(callback, "Отправлено родителям: " + result.delivered() + deliveryNote(result.failed(), result.demo()), null);
        return "✓ Отправлено";
    }

    // ", не доставлено: 2 · демо-аккаунтов: 24"
    static String deliveryNote(int failed, int demo) {
        return (failed > 0 ? ", не доставлено: " + failed : "") + (demo > 0 ? " · демо-аккаунтов: " + demo : "");
    }

    private void show(IncomingEvent.ButtonCallback callback, String text, Keyboard keyboard) {
        MessageRef message = callback.message();
        if (message != null) {
            sender.editMessage(message, text, keyboard);
        } else {
            sender.sendKeyboard(callback.callback().peerId(), text, keyboard);
        }
    }
}
