package ru.shtabklassa.bot.state;

import org.springframework.stereotype.Component;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.model.PollType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/*
 * Черновики в памяти, после рестарта теряются - для прототипа ок.
 * Переходы через compute, поэтому двойной "Отправить" отдаст черновик только одному.
 *
 * объявление: TEXT -> MODE -> CONFIRM
 * опрос: TYPE -> TEXT -> (OPTIONS для CHOICE) -> CONFIRM
 * событие: TYPE -> TEXT -> CONFIRM
 */
@Component
public class TeacherDraftStore {

    public enum Kind {
        ANNOUNCEMENT,
        POLL,
        EVENT
    }

    public enum Step {
        AWAITING_TYPE,
        AWAITING_TEXT,
        AWAITING_OPTIONS,
        AWAITING_MODE,
        CONFIRM
    }

    // text = текст объявления / вопрос / название события
    public record Draft(Kind kind, Step step, String text, boolean requiresDecision, PollType pollType,
                        List<String> options, EventType eventType, Instant startAt) {

        Draft(Kind kind, Step step, String text, boolean requiresDecision, PollType pollType, List<String> options) {
            this(kind, step, text, requiresDecision, pollType, options, null, null);
        }
    }

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    public void startAnnouncement(String teacherId) {
        drafts.put(teacherId, new Draft(Kind.ANNOUNCEMENT, Step.AWAITING_TEXT, null, false, null, List.of()));
    }

    public void startPoll(String teacherId) {
        drafts.put(teacherId, new Draft(Kind.POLL, Step.AWAITING_TYPE, null, false, null, List.of()));
    }

    public void startEvent(String teacherId) {
        drafts.put(teacherId, new Draft(Kind.EVENT, Step.AWAITING_TYPE, null, false, null, List.of()));
    }

    public Optional<Draft> chooseEventType(String teacherId, EventType type) {
        return transition(teacherId, Kind.EVENT, Step.AWAITING_TYPE, draft -> new Draft(Kind.EVENT, Step.AWAITING_TEXT,
                null, false, null, List.of(), type, null));
    }

    public Optional<Draft> acceptEvent(String teacherId, String title, Instant startAt) {
        return transition(teacherId, Kind.EVENT, Step.AWAITING_TEXT, draft -> new Draft(Kind.EVENT, Step.CONFIRM,
                title, false, null, List.of(), draft.eventType(), startAt));
    }

    public Optional<Draft> get(String teacherId) {
        return Optional.ofNullable(drafts.get(teacherId));
    }

    public Optional<Draft> choosePollType(String teacherId, PollType type) {
        return transition(teacherId, Kind.POLL, Step.AWAITING_TYPE,
                draft -> new Draft(Kind.POLL, Step.AWAITING_TEXT, null, false, type, List.of()));
    }

    public Optional<Draft> acceptText(String teacherId, String text) {
        Optional<Draft> announcement = transition(teacherId, Kind.ANNOUNCEMENT, Step.AWAITING_TEXT,
                draft -> new Draft(Kind.ANNOUNCEMENT, Step.AWAITING_MODE, text, false, null, List.of()));
        if (announcement.isPresent()) {
            return announcement;
        }
        return transition(teacherId, Kind.POLL, Step.AWAITING_TEXT, draft -> new Draft(Kind.POLL,
                draft.pollType() == PollType.CHOICE ? Step.AWAITING_OPTIONS : Step.CONFIRM,
                text, false, draft.pollType(), List.of()));
    }

    public Optional<Draft> acceptOptions(String teacherId, List<String> options) {
        return transition(teacherId, Kind.POLL, Step.AWAITING_OPTIONS,
                draft -> new Draft(Kind.POLL, Step.CONFIRM, draft.text(), false, draft.pollType(), List.copyOf(options)));
    }

    public Optional<Draft> chooseMode(String teacherId, boolean requiresDecision) {
        return transition(teacherId, Kind.ANNOUNCEMENT, Step.AWAITING_MODE,
                draft -> new Draft(Kind.ANNOUNCEMENT, Step.CONFIRM, draft.text(), requiresDecision, null, List.of()));
    }

    public Optional<Draft> takeConfirmed(String teacherId) {
        AtomicReference<Draft> taken = new AtomicReference<>();
        drafts.computeIfPresent(teacherId, (id, draft) -> {
            if (draft.step() != Step.CONFIRM) {
                return draft;
            }
            taken.set(draft);
            return null;
        });
        return Optional.ofNullable(taken.get());
    }

    // если отправка упала - чтобы не набирать заново
    public void restore(String teacherId, Draft draft) {
        drafts.putIfAbsent(teacherId, draft);
    }

    public boolean cancel(String teacherId) {
        return drafts.remove(teacherId) != null;
    }

    private Optional<Draft> transition(String teacherId, Kind kind, Step expected, UnaryOperator<Draft> next) {
        AtomicReference<Draft> updated = new AtomicReference<>();
        drafts.computeIfPresent(teacherId, (id, draft) -> {
            if (draft.kind() != kind || draft.step() != expected) {
                return draft;
            }
            Draft moved = next.apply(draft);
            updated.set(moved);
            return moved;
        });
        return Optional.ofNullable(updated.get());
    }
}
