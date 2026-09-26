package ru.shtabklassa.support;

import ru.shtabklassa.adapter.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

// failingPeers - кому доставка падает
public class FakeMessageSender implements MessageSender {

    public record Sent(String peerId, String text, Keyboard keyboard, String messageId) {
    }

    public record Edit(MessageRef message, String text, Keyboard keyboard) {
    }

    public record Answer(CallbackRef callback, String text) {
    }

    public record Document(String peerId, String fileName, byte[] content, String caption) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final List<Document> documents = new CopyOnWriteArrayList<>();
    public final List<Edit> edits = new CopyOnWriteArrayList<>();
    public final List<Answer> answers = new CopyOnWriteArrayList<>();
    public final Set<String> failingPeers = ConcurrentHashMap.newKeySet();
    // отдельно от failingPeers - отправка работает, правка нет
    public final Set<String> failingEdits = ConcurrentHashMap.newKeySet();
    // медленная загрузка файла, чтобы второй клик успел прилететь
    public volatile long documentUploadMillis;
    private final AtomicLong nextId = new AtomicLong(1000);

    public void reset() {
        sent.clear();
        documents.clear();
        documentUploadMillis = 0;
        failingEdits.clear();
        edits.clear();
        answers.clear();
        failingPeers.clear();
    }

    @Override
    public MessageRef sendMessage(String peerId, String text) {
        return sendKeyboard(peerId, text, null);
    }

    @Override
    public MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard) {
        if (failingPeers.contains(peerId)) {
            throw new MessageDeliveryException("fake: " + peerId + " недоступен");
        }
        String id = Long.toString(nextId.getAndIncrement());
        sent.add(new Sent(peerId, text, keyboard, id));
        return new MessageRef(peerId, id);
    }

    @Override
    public List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard) {
        return peerIds.stream().map(peerId -> failingPeers.contains(peerId)
                ? DeliveryResult.failed(peerId, "901: fake")
                : DeliveryResult.delivered(sendKeyboard(peerId, text, keyboard))).toList();
    }

    @Override
    public MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption) {
        if (failingPeers.contains(peerId)) {
            throw new MessageDeliveryException("fake: файл для " + peerId + " не загрузился");
        }
        if (documentUploadMillis > 0) {
            try {
                Thread.sleep(documentUploadMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        documents.add(new Document(peerId, fileName, content, caption));
        return new MessageRef(peerId, Long.toString(nextId.getAndIncrement()));
    }

    @Override
    public void editMessage(MessageRef message, String text, Keyboard keyboard) {
        if (failingEdits.contains(message.peerId())) {
            throw new MessageDeliveryException("fake: правка у " + message.peerId() + " не прошла");
        }
        edits.add(new Edit(message, text, keyboard));
    }

    @Override
    public void answerCallback(CallbackRef callback, String text) {
        answers.add(new Answer(callback, text));
    }

    public List<Sent> sentTo(String peerId) {
        return sent.stream().filter(message -> message.peerId().equals(peerId)).toList();
    }

    public String lastEditText() {
        return edits.isEmpty() ? null : edits.getLast().text();
    }
}
