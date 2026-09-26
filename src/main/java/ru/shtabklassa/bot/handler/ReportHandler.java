package ru.shtabklassa.bot.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.ReportService;
import ru.shtabklassa.service.SummaryTarget;

@Component
public class ReportHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportHandler.class);

    static final String SENT = "📥 Отчёт отправлен";
    static final String FAILED = "Не получилось отправить отчёт, попробуйте ещё раз.";

    private final ReportService reports;

    public ReportHandler(ReportService reports) {
        this.reports = reports;
    }

    String handle(User teacher, Payload payload) {
        if (payload.id() == null) {
            return ParentAnswerHandler.STALE_BUTTON;
        }
        SummaryTarget target = payload.a() == Payload.Action.REPORT_POLL
                ? SummaryTarget.poll(payload.id())
                : SummaryTarget.announcement(payload.id());
        try {
            reports.send(teacher.getExternalId(), target);
        } catch (MessageDeliveryException e) {
            // загрузка файла самое хрупкое место, учителю по-человечески, детали в лог
            log.warn("отчёт {} не отправлен: {}", target, e.getMessage());
            return FAILED;
        }
        return SENT;
    }
}
