package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.model.*;
import ru.shtabklassa.repository.PollAnswerRepository;
import ru.shtabklassa.repository.PollDeliveryRepository;
import ru.shtabklassa.repository.PollRepository;
import ru.shtabklassa.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PollService {

    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    static final int MAX_QUESTION_LENGTH = 1000;
    static final int MAX_ANSWER_LENGTH = 1000;
    static final int MIN_OPTIONS = 2;
    // VK: не больше 6 строк в inline клаве и 40 символов на кнопке
    static final int MAX_OPTIONS = 6;
    static final int MAX_OPTION_LENGTH = 40;

    public record AnswerResult(MarkOutcome outcome, String label) {
    }

    private final PollRepository polls;
    private final PollAnswerRepository answers;
    private final PollDeliveryRepository deliveries;
    private final UserRepository users;
    private final MessageSender sender;
    private final ParentBroadcaster broadcaster;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PollService(PollRepository polls, PollAnswerRepository answers, PollDeliveryRepository deliveries,
                       UserRepository users, MessageSender sender, ParentBroadcaster broadcaster,
                       ApplicationEventPublisher events, Clock clock) {
        this.polls = polls;
        this.answers = answers;
        this.deliveries = deliveries;
        this.users = users;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.events = events;
        this.clock = clock;
    }

    // всё как в AnnouncementService.publish. options нужны только для CHOICE
    public PublishResult publish(String teacherExternalId, PollType type, String question, List<String> options) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("Опросы создаёт только классный руководитель.");
        }
        if (teacher.getClassId() == null) {
            throw new DomainException.NotAllowed("Вы не привязаны к классу.");
        }
        String checkedQuestion = validateQuestion(question);
        List<String> checkedOptions = type == PollType.CHOICE ? validateOptions(options) : List.of();

        Poll poll = polls.save(new Poll(type, checkedQuestion, checkedOptions, teacher.getClassId(), teacher.getId(),
                clock.instant()));

        PollSummary initial = summarize(poll.getId());
        MessageRef summaryMessage = sender.sendKeyboard(teacherExternalId, initial.render(),
                Keyboards.teacherSummary(SummaryTarget.poll(poll.getId()), initial.silent()));
        poll.setSummaryMessageId(summaryMessage.messageId());
        poll = polls.save(poll);

        List<User> parents = users.findByClassIdAndRoleOrderByFullName(teacher.getClassId(), Role.PARENT);
        ParentBroadcaster.Tally tally = deliver(poll, parents, messageFor(poll, teacher));
        if (tally.failed() > 0) {
            log.warn("опрос {}: не доставлено {} из {}", poll.getId(), tally.failed(), parents.size());
        }
        if (tally.failed() > 0 || tally.demo() > 0) {
            // см. AnnouncementService.publish - тут не кидать
            try {
                pushSummary(poll.getId());
            } catch (MessageDeliveryException e) {
                log.warn("опрос {}: разослан, но сводка не обновилась: {}", poll.getId(), e.getMessage());
            }
        }
        return PublishResult.of(poll.getId(), tally);
    }

    String messageFor(Poll poll, User author) {
        String hint = switch (poll.getType()) {
            case CONSENT, CHOICE -> "";
            case CUSTOM -> "\n\nНажмите «Ответить» и напишите ответ одним сообщением.";
        };
        return "📋 " + author.getFullName() + ":\n\n" + poll.getQuestion() + hint;
    }

    public ParentCard parentCard(long pollId, String parentExternalId) {
        Poll poll = polls.findById(pollId).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
        User parent = users.findByExternalId(parentExternalId).orElseThrow(DomainException.UnknownUser::new);
        String text = messageFor(poll, users.findById(poll.getAuthorId()).orElseThrow());
        String mine = answers.findById(new PollAnswer.Key(pollId, parent.getId()))
                .map(answer -> answerLabel(poll, answer.getAnswer()))
                .orElse(null);
        if (mine == null) {
            return new ParentCard(text, Keyboards.forPoll(poll));
        }
        if (poll.getType() == PollType.CUSTOM) {
            mine = "«" + (mine.length() <= 100 ? mine : mine.substring(0, 99) + "…") + "»";
        }
        return new ParentCard(text + "\n\n✓ Ваш ответ: " + mine, Keyboards.forPoll(poll));
    }

    public static String answerLabel(Poll poll, String stored) {
        return switch (poll.getType()) {
            case CONSENT -> Poll.YES.equals(stored) ? "Да" : "Нет";
            case CHOICE -> {
                // кривой номер покажем как есть, чтобы не терять
                try {
                    yield poll.getOptions().get(Integer.parseInt(stored));
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    yield stored;
                }
            }
            case CUSTOM -> stored;
        };
    }

    // последнее дошедшее сообщение с опросом - его перерисовываем когда ответ пришёл текстом
    public Optional<MessageRef> deliveredMessage(long pollId, String parentExternalId) {
        return users.findByExternalId(parentExternalId)
                .flatMap(parent -> deliveries.findById(new PollDelivery.Key(pollId, parent.getId())))
                .filter(delivery -> delivery.getMessageId() != null)
                .map(delivery -> new MessageRef(parentExternalId, delivery.getMessageId()));
    }

    ParentBroadcaster.Tally deliver(Poll poll, List<User> recipients, String text) {
        Instant now = clock.instant();
        List<ParentBroadcaster.Outcome> outcomes = broadcaster.send(recipients, text, Keyboards.forPoll(poll));
        List<PollDelivery> rows = outcomes.stream()
                .map(outcome -> new PollDelivery(poll.getId(), outcome.recipient().getId(), outcome.messageId(),
                        outcome.failedReason(), now))
                .toList();
        deliveries.saveAll(rows);
        return ParentBroadcaster.Tally.of(outcomes);
    }

    public static String validateQuestion(String question) {
        String trimmed = question == null ? "" : question.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException.InvalidInput("Вопрос пустой.");
        }
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new DomainException.InvalidInput("Слишком длинно: максимум " + MAX_QUESTION_LENGTH + " символов.");
        }
        return trimmed;
    }

    // варианты одним сообщением, каждый с новой строки
    public static List<String> parseOptions(String raw) {
        List<String> options = raw == null ? List.of() : raw.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        return validateOptions(options);
    }

    private static List<String> validateOptions(List<String> options) {
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new DomainException.InvalidInput("Нужно от " + MIN_OPTIONS + " до " + MAX_OPTIONS
                    + " вариантов, каждый с новой строки.");
        }
        Set<String> seen = new HashSet<>();
        for (String option : options) {
            if (option.length() > MAX_OPTION_LENGTH) {
                throw new DomainException.InvalidInput("Вариант «" + option.substring(0, 20) + "…» длиннее "
                        + MAX_OPTION_LENGTH + " символов — на кнопку не влезет.");
            }
            if (!seen.add(option.toLowerCase(Locale.ROOT))) {
                throw new DomainException.InvalidInput("Вариант «" + option + "» повторяется.");
            }
        }
        return List.copyOf(options);
    }

    // value: YES/NO, номер варианта или текст. идемпотентно как mark() у объявлений
    @Transactional
    public AnswerResult answer(long pollId, String parentExternalId, String value) {
        User parent = users.findByExternalId(parentExternalId).orElseThrow(DomainException.UnknownUser::new);
        Poll poll = requireAnswerable(pollId, parent);

        String stored;
        String label;
        switch (poll.getType()) {
            case CONSENT -> {
                if (!Poll.YES.equals(value) && !Poll.NO.equals(value)) {
                    throw new DomainException.InvalidInput("Кнопка устарела.");
                }
                stored = value;
                label = Poll.YES.equals(value) ? "да" : "нет";
            }
            case CHOICE -> {
                int option = parseOptionIndex(value, poll.getOptions().size());
                stored = Integer.toString(option);
                label = poll.getOptions().get(option);
            }
            case CUSTOM -> {
                stored = value == null ? "" : value.strip();
                if (stored.isEmpty()) {
                    throw new DomainException.InvalidInput("Пустой ответ.");
                }
                if (stored.length() > MAX_ANSWER_LENGTH) {
                    throw new DomainException.InvalidInput("Слишком длинно: максимум " + MAX_ANSWER_LENGTH + " символов.");
                }
                label = stored.length() <= 30 ? stored : stored.substring(0, 29) + "…";
            }
            default -> throw new IllegalStateException(poll.getType().name());
        }

        Instant now = clock.instant();
        MarkOutcome outcome;
        if (answers.insertIfAbsent(pollId, parent.getId(), stored, now) == 1) {
            outcome = MarkOutcome.RECORDED;
        } else if (answers.updateIfChanged(pollId, parent.getId(), stored, now) == 1) {
            outcome = MarkOutcome.CHANGED;
        } else {
            outcome = MarkOutcome.UNCHANGED;
        }
        if (outcome != MarkOutcome.UNCHANGED) {
            events.publishEvent(new AnswersChanged(SummaryTarget.poll(pollId)));
        }
        return new AnswerResult(outcome, label);
    }

    private static int parseOptionIndex(String value, int optionCount) {
        try {
            int option = Integer.parseInt(value);
            if (option >= 0 && option < optionCount) {
                return option;
            }
        } catch (NumberFormatException ignored) {
            // ниже
        }
        throw new DomainException.InvalidInput("Кнопка устарела.");
    }

    // проверить до того, как просить родителя написать ответ
    @Transactional(readOnly = true)
    public Poll requireAnswerable(long pollId, String parentExternalId) {
        User parent = users.findByExternalId(parentExternalId).orElseThrow(DomainException.UnknownUser::new);
        return requireAnswerable(pollId, parent);
    }

    private Poll requireAnswerable(long pollId, User parent) {
        if (parent.getRole() != Role.PARENT) {
            throw new DomainException.NotAllowed("Отвечать могут только родители.");
        }
        Poll poll = polls.findById(pollId).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
        if (!Objects.equals(poll.getClassId(), parent.getClassId())) {
            throw new DomainException.NotAllowed("Это опрос другого класса.");
        }
        if (poll.isClosed()) {
            throw new DomainException.InvalidInput("Опрос закрыт.");
        }
        return poll;
    }

    public PollSummary summarize(long pollId) {
        Poll poll = polls.findById(pollId).orElseThrow(() -> new DomainException.NotFound("Опрос не найден."));
        long totalParents = users.countByClassIdAndRole(poll.getClassId(), Role.PARENT);
        long answered = answers.countByPollId(pollId);

        Map<String, Long> byAnswer = new HashMap<>();
        for (Object[] row : answers.countByAnswer(pollId)) {
            byAnswer.put((String) row[0], (Long) row[1]);
        }
        List<PollSummary.Count> counts = new ArrayList<>();
        List<String> recent = List.of();
        switch (poll.getType()) {
            case CONSENT -> {
                counts.add(new PollSummary.Count("Да", byAnswer.getOrDefault(Poll.YES, 0L)));
                counts.add(new PollSummary.Count("Нет", byAnswer.getOrDefault(Poll.NO, 0L)));
            }
            case CHOICE -> {
                List<String> options = poll.getOptions();
                for (int i = 0; i < options.size(); i++) {
                    counts.add(new PollSummary.Count(options.get(i), byAnswer.getOrDefault(Integer.toString(i), 0L)));
                }
            }
            case CUSTOM -> recent = recentAnswers(pollId);
        }
        long demo = deliveries.countByPollIdAndFailedReason(pollId, ParentBroadcaster.DEMO_REASON);
        return new PollSummary(pollId, poll.getQuestion(), poll.getType(), totalParents, answered, counts, recent,
                deliveries.countByPollIdAndFailedReasonIsNotNull(pollId) - demo, demo);
    }

    private List<String> recentAnswers(long pollId) {
        List<PollAnswer> latest = answers.findTop3ByPollIdOrderByAnsweredAtDesc(pollId);
        Map<Long, String> names = users.findAllById(latest.stream().map(PollAnswer::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return latest.stream()
                .map(answer -> {
                    String text = answer.getAnswer().length() <= 60 ? answer.getAnswer() : answer.getAnswer().substring(0, 59) + "…";
                    return "«" + text + "» — " + names.getOrDefault(answer.getUserId(), "?");
                })
                .toList();
    }

    public void pushSummary(long pollId) {
        Poll poll = polls.findById(pollId).orElse(null);
        if (poll == null || poll.getSummaryMessageId() == null) {
            return;
        }
        User author = users.findById(poll.getAuthorId()).orElseThrow();
        PollSummary summary = summarize(pollId);
        sender.editMessage(new MessageRef(author.getExternalId(), poll.getSummaryMessageId()), summary.render(),
                Keyboards.teacherSummary(SummaryTarget.poll(pollId), summary.silent()));
    }
}
