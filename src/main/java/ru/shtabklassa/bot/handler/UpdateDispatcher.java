package ru.shtabklassa.bot.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.IncomingEventListener;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.filter.KnownUserFilter;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.DomainException;

@Component
public class UpdateDispatcher implements IncomingEventListener {

    private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

    static final String PARENT_TEXT_REPLY = "Отвечать ничего не нужно — просто нажимайте кнопки под объявлениями. "
            + "Календарь класса — кнопкой внизу.";
    static final String GENERIC_FAILURE = "Не получилось, попробуйте ещё раз.";

    private final KnownUserFilter userFilter;
    private final ParentAnswerHandler parentAnswers;
    private final TeacherDialogHandler teacherDialog;
    private final ReminderHandler reminders;
    private final CalendarHandler calendar;
    private final ReportHandler reports;
    private final WelcomeHandler welcome;
    private final MessageSender sender;

    public UpdateDispatcher(KnownUserFilter userFilter, ParentAnswerHandler parentAnswers,
                            TeacherDialogHandler teacherDialog, ReminderHandler reminders, CalendarHandler calendar,
                            ReportHandler reports, WelcomeHandler welcome, MessageSender sender) {
        this.userFilter = userFilter;
        this.parentAnswers = parentAnswers;
        this.teacherDialog = teacherDialog;
        this.reminders = reminders;
        this.calendar = calendar;
        this.reports = reports;
        this.welcome = welcome;
        this.sender = sender;
    }

    @Override
    public void onEvent(IncomingEvent event) {
        switch (event) {
            case IncomingEvent.TextMessage message -> onText(message);
            case IncomingEvent.ButtonCallback callback -> onCallback(callback);
        }
    }

    private void onText(IncomingEvent.TextMessage message) {
        User user = userFilter.resolve(message.fromId()).orElse(null);
        try {
            if (user == null) {
                // отсюда берём user_id телефонов для seed.teacher-id / parent-ids
                log.info("пишет незнакомый пользователь, id={}", message.fromId());
                sender.sendMessage(message.peerId(), KnownUserFilter.UNKNOWN_USER_REPLY);
            } else if (WelcomeHandler.isStart(message)) {
                // Начать = с чистого листа, иначе брошенный черновик съест следующий текст
                teacherDialog.dropDraft(user);
                parentAnswers.forgetPendingAnswer(user);
                welcome.welcome(user, message.peerId());
            } else if (user.getRole() == Role.TEACHER) {
                teacherDialog.onText(user, message);
            } else if (CalendarHandler.isMenuButton(message, Payload.Action.CAL_VIEW, Keyboards.CALENDAR_LABEL)) {
                // до parentAnswers! иначе кнопка меню запишется ответом на опрос
                calendar.showCalendar(user, message.peerId());
            } else if (!parentAnswers.onText(user, message)) {
                sender.sendKeyboard(message.peerId(), PARENT_TEXT_REPLY, Keyboards.parentMenu());
            }
        } catch (DomainException e) {
            replyQuietly(message.peerId(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("сообщение от {} не обработано", message.fromId(), e);
            replyQuietly(message.peerId(), GENERIC_FAILURE);
        }
    }

    // транспорт лёг - ответить всё равно не выйдет
    private void replyQuietly(String peerId, String text) {
        try {
            sender.sendMessage(peerId, text);
        } catch (MessageDeliveryException e) {
            log.warn("не ответили {}: {}", peerId, e.getMessage());
        }
    }

    // отвечаем на нажатие всегда, иначе у человека крутится индикатор на кнопке
    private void onCallback(IncomingEvent.ButtonCallback callback) {
        String reply;
        try {
            reply = route(callback);
        } catch (DomainException e) {
            reply = e.getMessage();
        } catch (RuntimeException e) {
            log.error("нажатие {} не обработано", callback, e);
            reply = GENERIC_FAILURE;
        }
        try {
            sender.answerCallback(callback.callback(), reply);
        } catch (MessageDeliveryException e) {
            log.warn("не ответили на нажатие {}: {}", callback.callback().eventId(), e.getMessage());
        }
    }

    private String route(IncomingEvent.ButtonCallback callback) {
        User user = userFilter.resolve(callback.callback().userId()).orElse(null);
        if (user == null) {
            return KnownUserFilter.UNKNOWN_USER_REPLY;
        }
        Payload payload = Payload.parse(callback.payload()).orElse(null);
        if (payload == null) {
            return ParentAnswerHandler.STALE_BUTTON;
        }
        boolean teacher = user.getRole() == Role.TEACHER;
        return switch (payload.a()) {
            case ANN_READ, ANN_AGREE, ANN_DECLINE, POLL_YES, POLL_NO, POLL_OPT, POLL_TEXT ->
                    parentAnswers.handle(user, payload, callback);
            case MODE_READ, MODE_DECISION, PTYPE_CONSENT, PTYPE_CHOICE, PTYPE_CUSTOM, DRAFT_SEND, DRAFT_CANCEL,
                 EVT_NEW, ETYPE_MEETING, ETYPE_EVENT ->
                    teacher ? teacherDialog.onCallback(user, callback, payload) : ParentAnswerHandler.STALE_BUTTON;
            case REMIND_ANN, REMIND_POLL -> teacher ? reminders.handle(user, payload) : ParentAnswerHandler.STALE_BUTTON;
            case SILENT_ANN, SILENT_POLL -> teacher ? reminders.silentList(user, payload) : ParentAnswerHandler.STALE_BUTTON;
            case DUTY_PLAN -> teacher ? calendar.planDuties(user, callback) : ParentAnswerHandler.STALE_BUTTON;
            case REPORT_ANN, REPORT_POLL -> teacher ? reports.handle(user, payload) : ParentAnswerHandler.STALE_BUTTON;
            // меню приходит текстом, не колбэком
            case NEW_ANN, NEW_POLL, CAL_VIEW, DUTY_VIEW -> ParentAnswerHandler.STALE_BUTTON;
        };
    }
}
