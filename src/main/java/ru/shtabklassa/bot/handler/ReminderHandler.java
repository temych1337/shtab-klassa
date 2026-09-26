package ru.shtabklassa.bot.handler;

import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.ReminderService;
import ru.shtabklassa.service.SummaryTarget;

@Component
public class ReminderHandler {

    static final String ALL_ANSWERED = "Все уже ответили 🎉";

    private final ReminderService reminders;
    private final MessageSender sender;

    public ReminderHandler(ReminderService reminders, MessageSender sender) {
        this.reminders = reminders;
        this.sender = sender;
    }

    String handle(User teacher, Payload payload) {
        if (payload.id() == null) {
            return ParentAnswerHandler.STALE_BUTTON;
        }
        SummaryTarget target = payload.a() == Payload.Action.REMIND_POLL
                ? SummaryTarget.poll(payload.id())
                : SummaryTarget.announcement(payload.id());
        ReminderService.ReminderResult result = reminders.remindSilent(teacher.getExternalId(), target);
        if (result.nobodySilent()) {
            return ALL_ANSWERED;
        }
        return "🔔 Напомнили: " + result.reminded() + TeacherDialogHandler.deliveryNote(result.failed(), result.demo());
    }

    String silentList(User teacher, Payload payload) {
        if (payload.id() == null) {
            return ParentAnswerHandler.STALE_BUTTON;
        }
        SummaryTarget target = payload.a() == Payload.Action.SILENT_POLL
                ? SummaryTarget.poll(payload.id())
                : SummaryTarget.announcement(payload.id());
        ReminderService.SilentList list = reminders.silentList(teacher.getExternalId(), target);
        if (list.names().isEmpty()) {
            return ALL_ANSWERED;
        }
        if (list.repeated()) {
            return "Список уже выше в чате";
        }
        sender.sendMessage(teacher.getExternalId(), list.render());
        return "👥 Не ответили: " + list.names().size();
    }
}
