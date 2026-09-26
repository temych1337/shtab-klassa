package ru.shtabklassa.bot.handler;

import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.CalendarService;
import ru.shtabklassa.service.DutyService;

@Component
public class CalendarHandler {

    private final CalendarService calendar;
    private final DutyService duties;
    private final MessageSender sender;

    public CalendarHandler(CalendarService calendar, DutyService duties, MessageSender sender) {
        this.calendar = calendar;
        this.duties = duties;
        this.sender = sender;
    }

    static boolean isMenuButton(IncomingEvent.TextMessage message, Payload.Action action, String label) {
        return Payload.parse(message.payload()).map(payload -> payload.a() == action).orElse(label.equals(message.text()));
    }

    void showCalendar(User user, String peer) {
        String agenda = calendar.agenda(user.getExternalId()).render();
        sender.sendKeyboard(peer, agenda,
                user.getRole() == Role.TEACHER ? Keyboards.calendarActions() : Keyboards.parentMenu());
    }

    void showDuties(User teacher, String peer) {
        sender.sendKeyboard(peer, duties.overview(teacher.getExternalId()), Keyboards.dutyActions());
    }

    String planDuties(User teacher, IncomingEvent.ButtonCallback callback) {
        DutyService.PlanResult result = duties.planTwoWeeks(teacher.getExternalId());
        String overview = duties.overview(teacher.getExternalId());
        if (callback.message() != null) {
            sender.editMessage(callback.message(), overview, Keyboards.dutyActions());
        } else {
            sender.sendKeyboard(callback.callback().peerId(), overview, Keyboards.dutyActions());
        }
        return result.created() > 0
                ? "✓ Добавлено дежурств: " + result.created()
                : "График на 2 недели уже составлен";
    }
}
