package ru.shtabklassa.bot.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// после "Ответить" следующий текст родителя = ответ на опрос. в памяти, после рестарта жать заново
@Component
public class ParentReplyStore {

    private final Map<String, Long> awaitingPoll = new ConcurrentHashMap<>();

    public void await(String parentId, long pollId) {
        awaitingPoll.put(parentId, pollId);
    }

    public Optional<Long> take(String parentId) {
        return Optional.ofNullable(awaitingPoll.remove(parentId));
    }
}
