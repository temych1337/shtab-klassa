package ru.shtabklassa.bot.keyboard;

import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.Keyboard.Button;
import ru.shtabklassa.adapter.Keyboard.Color;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.service.SummaryTarget;

import java.util.ArrayList;
import java.util.List;

import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

public final class Keyboards {

    public static final String NEW_ANNOUNCEMENT_LABEL = "📢 Новое объявление";
    public static final String NEW_POLL_LABEL = "📋 Новый опрос";
    public static final String CALENDAR_LABEL = "📅 Календарь";
    public static final String DUTIES_LABEL = "🧹 Дежурства";

    private Keyboards() {
    }

    public static Keyboard forAnnouncement(long announcementId, boolean requiresDecision) {
        if (requiresDecision) {
            return Keyboard.inline(List.of(
                    Button.callback("Согласен", Payload.of(ANN_AGREE, announcementId).toJson(), Color.POSITIVE),
                    Button.callback("Не смогу", Payload.of(ANN_DECLINE, announcementId).toJson(), Color.NEGATIVE)));
        }
        return Keyboard.inline(List.of(
                Button.callback("👀 Прочитал", Payload.of(ANN_READ, announcementId).toJson(), Color.PRIMARY)));
    }

    public static Keyboard forPoll(Poll poll) {
        long id = poll.getId();
        return switch (poll.getType()) {
            case CONSENT -> Keyboard.inline(List.of(
                    Button.callback("Да", Payload.of(POLL_YES, id).toJson(), Color.POSITIVE),
                    Button.callback("Нет", Payload.of(POLL_NO, id).toJson(), Color.NEGATIVE)));
            case CHOICE -> {
                List<List<Button>> rows = new ArrayList<>();
                List<String> options = poll.getOptions();
                for (int i = 0; i < options.size(); i++) {
                    rows.add(List.of(Button.callback(options.get(i), Payload.option(id, i).toJson(), Color.PRIMARY)));
                }
                yield new Keyboard(rows, true);
            }
            case CUSTOM -> Keyboard.inline(List.of(
                    Button.callback("✏ Ответить", Payload.of(POLL_TEXT, id).toJson(), Color.PRIMARY)));
        };
    }

    // когда молчащих нет - остаётся только отчёт
    public static Keyboard teacherSummary(SummaryTarget target, long silentParents) {
        boolean poll = target.kind() == SummaryTarget.Kind.POLL;
        Button report = Button.callback("📥 Скачать отчёт",
                Payload.of(poll ? REPORT_POLL : REPORT_ANN, target.id()).toJson(), Color.SECONDARY);
        if (silentParents <= 0) {
            return Keyboard.inline(List.of(report));
        }
        return Keyboard.inline(
                List.of(Button.callback("🔔 Напомнить молчащим (" + silentParents + ")",
                        Payload.of(poll ? REMIND_POLL : REMIND_ANN, target.id()).toJson(), Color.PRIMARY)),
                List.of(report, Button.callback("👥 Кто не ответил",
                        Payload.of(poll ? SILENT_POLL : SILENT_ANN, target.id()).toJson(), Color.SECONDARY)));
    }

    public static Keyboard teacherMenu() {
        return Keyboard.menu(
                List.of(Button.text(NEW_ANNOUNCEMENT_LABEL, Payload.of(NEW_ANN).toJson(), Color.PRIMARY),
                        Button.text(NEW_POLL_LABEL, Payload.of(NEW_POLL).toJson(), Color.PRIMARY)),
                List.of(Button.text(CALENDAR_LABEL, Payload.of(CAL_VIEW).toJson(), Color.SECONDARY),
                        Button.text(DUTIES_LABEL, Payload.of(DUTY_VIEW).toJson(), Color.SECONDARY)));
    }

    public static Keyboard parentMenu() {
        return Keyboard.menu(List.of(Button.text(CALENDAR_LABEL, Payload.of(CAL_VIEW).toJson(), Color.SECONDARY)));
    }

    public static Keyboard calendarActions() {
        return Keyboard.inline(List.of(Button.callback("➕ Новое событие", Payload.of(EVT_NEW).toJson(), Color.PRIMARY)));
    }

    public static Keyboard chooseEventType() {
        return Keyboard.inline(
                List.of(Button.callback("👥 Собрание", Payload.of(ETYPE_MEETING).toJson(), Color.PRIMARY),
                        Button.callback("🎉 Мероприятие", Payload.of(ETYPE_EVENT).toJson(), Color.PRIMARY)),
                List.of(cancelButton()));
    }

    public static Keyboard dutyActions() {
        return Keyboard.inline(List.of(
                Button.callback("Составить на 2 недели", Payload.of(DUTY_PLAN).toJson(), Color.PRIMARY)));
    }

    public static Keyboard cancelDraft() {
        return Keyboard.inline(List.of(cancelButton()));
    }

    public static Keyboard chooseMode() {
        return Keyboard.inline(
                List.of(Button.callback("Только «Прочитал»", Payload.of(MODE_READ).toJson(), Color.PRIMARY)),
                List.of(Button.callback("«Согласен / Не смогу»", Payload.of(MODE_DECISION).toJson(), Color.PRIMARY)),
                List.of(cancelButton()));
    }

    public static Keyboard choosePollType() {
        return Keyboard.inline(
                List.of(Button.callback("Да / Нет", Payload.of(PTYPE_CONSENT).toJson(), Color.PRIMARY)),
                List.of(Button.callback("Выбор из вариантов", Payload.of(PTYPE_CHOICE).toJson(), Color.PRIMARY)),
                List.of(Button.callback("Свободный ответ", Payload.of(PTYPE_CUSTOM).toJson(), Color.PRIMARY)),
                List.of(cancelButton()));
    }

    public static Keyboard confirmDraft() {
        return Keyboard.inline(List.of(
                Button.callback("Отправить", Payload.of(DRAFT_SEND).toJson(), Color.POSITIVE),
                cancelButton()));
    }

    private static Button cancelButton() {
        return Button.callback("Отмена", Payload.of(DRAFT_CANCEL).toJson(), Color.SECONDARY);
    }
}
