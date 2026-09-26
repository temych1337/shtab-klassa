package ru.shtabklassa.bot.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.bot.state.ParentReplyStore;
import ru.shtabklassa.model.Decision;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.AnnouncementService;
import ru.shtabklassa.service.DomainException;
import ru.shtabklassa.service.MarkOutcome;
import ru.shtabklassa.service.ParentCard;
import ru.shtabklassa.service.PollService;

import java.util.function.Supplier;

// возвращает текст всплывашки
@Component
public class ParentAnswerHandler {

    private static final Logger log = LoggerFactory.getLogger(ParentAnswerHandler.class);

    static final String STALE_BUTTON = "Кнопка устарела.";

    private final AnnouncementService announcements;
    private final PollService polls;
    private final ParentReplyStore replies;
    private final MessageSender sender;

    public ParentAnswerHandler(AnnouncementService announcements, PollService polls, ParentReplyStore replies,
                               MessageSender sender) {
        this.announcements = announcements;
        this.polls = polls;
        this.replies = replies;
        this.sender = sender;
    }

    String handle(User parent, Payload payload, IncomingEvent.ButtonCallback callback) {
        if (payload.id() == null) {
            return STALE_BUTTON;
        }
        long id = payload.id();
        String parentId = parent.getExternalId();
        return switch (payload.a()) {
            case ANN_READ -> mark(id, parentId, null, "прочитал", callback);
            case ANN_AGREE -> mark(id, parentId, Decision.AGREE, "согласен", callback);
            case ANN_DECLINE -> mark(id, parentId, Decision.DECLINE, "не смогу", callback);
            case POLL_YES -> answer(id, parentId, Poll.YES, callback);
            case POLL_NO -> answer(id, parentId, Poll.NO, callback);
            case POLL_OPT -> payload.o() == null ? STALE_BUTTON : answer(id, parentId, payload.o().toString(), callback);
            case POLL_TEXT -> {
                Poll poll = polls.requireAnswerable(id, parentId);
                if (poll.getType() != PollType.CUSTOM) {
                    yield STALE_BUTTON;
                }
                replies.await(parentId, id);
                sender.sendMessage(callback.callback().peerId(),
                        "Напишите ответ на «" + poll.getQuestion() + "» одним сообщением.");
                yield "Жду ответ";
            }
            default -> STALE_BUTTON;
        };
    }

    private String mark(long announcementId, String parentId, Decision decision, String label,
                        IncomingEvent.ButtonCallback callback) {
        MarkOutcome outcome = announcements.mark(announcementId, parentId, decision);
        if (outcome != MarkOutcome.UNCHANGED) {
            redraw(callback.message(), () -> announcements.parentCard(announcementId, parentId));
        }
        return snackbar(outcome, label);
    }

    private String answer(long pollId, String parentId, String value, IncomingEvent.ButtonCallback callback) {
        PollService.AnswerResult result = polls.answer(pollId, parentId, value);
        if (result.outcome() != MarkOutcome.UNCHANGED) {
            redraw(callback.message(), () -> polls.parentCard(pollId, parentId));
        }
        return snackbar(result.outcome(), result.label());
    }

    // всплывашка пропадает через пару секунд, а ответ должен остаться виден в сообщении.
    // ответ уже записан, так что если не перерисовалось - просто лог
    private void redraw(MessageRef message, Supplier<ParentCard> card) {
        if (message == null) {
            return;
        }
        try {
            ParentCard current = card.get();
            sender.editMessage(message, current.text(), current.keyboard());
        } catch (MessageDeliveryException e) {
            log.warn("не обновилось сообщение у родителя {}: {}", message.peerId(), e.getMessage());
        }
    }

    private static String snackbar(MarkOutcome outcome, String answer) {
        return switch (outcome) {
            case RECORDED -> "✓ Отмечено: " + answer;
            case CHANGED -> "✓ Ответ изменён: " + answer;
            case UNCHANGED -> "Уже отмечено: " + answer;
        };
    }

    void forgetPendingAnswer(User parent) {
        replies.take(parent.getExternalId());
    }

    // false = ответа не ждали, текст не наш
    boolean onText(User parent, IncomingEvent.TextMessage message) {
        String parentId = parent.getExternalId();
        Long pollId = replies.take(parentId).orElse(null);
        if (pollId == null) {
            return false;
        }
        PollService.AnswerResult result;
        try {
            result = polls.answer(pollId, parentId, message.text());
        } catch (DomainException.InvalidInput e) {
            // пусть пришлёт ещё раз без повторного "Ответить"
            replies.await(parentId, pollId);
            sender.sendMessage(message.peerId(), e.getMessage() + " Пришлите ещё раз.");
            return true;
        }
        sender.sendMessage(message.peerId(), switch (result.outcome()) {
            case RECORDED -> "✓ Ответ записан.";
            case CHANGED -> "✓ Ответ изменён.";
            case UNCHANGED -> "Такой ответ уже записан.";
        });
        if (result.outcome() != MarkOutcome.UNCHANGED) {
            redraw(polls.deliveredMessage(pollId, parentId).orElse(null), () -> polls.parentCard(pollId, parentId));
        }
        return true;
    }
}
