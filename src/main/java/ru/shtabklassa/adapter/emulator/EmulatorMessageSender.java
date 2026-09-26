package ru.shtabklassa.adapter.emulator;

import ru.shtabklassa.adapter.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// "мессенджер" в памяти для показа без MAX, страница /emulator/ забирает по http. рестарт = переписка пропала
public class EmulatorMessageSender implements MessageSender {

    static final int MAX_MESSAGES_PER_CHAT = 200;

    // mine - написал сам человек, рисуется справа
    public record ChatMessage(String id, String text, Keyboard keyboard, String fileId, String fileName,
                              boolean mine, long sentAt) {
    }

    public record StoredFile(String name, byte[] content) {
    }

    private final Map<String, List<ChatMessage>> chats = new ConcurrentHashMap<>();
    private final Map<String, String> callbackAnswers = new ConcurrentHashMap<>();
    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextId = new AtomicLong(1);
    // любое изменение двигает ревизию, страница перерисовывает только если она сменилась
    private final AtomicLong revision = new AtomicLong();

    @Override
    public MessageRef sendMessage(String peerId, String text) {
        return sendKeyboard(peerId, text, null);
    }

    @Override
    public MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard) {
        return append(peerId, text, keyboard, null, null);
    }

    @Override
    public List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard) {
        return peerIds.stream().map(peerId -> DeliveryResult.delivered(sendKeyboard(peerId, text, keyboard))).toList();
    }

    @Override
    public MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption) {
        String fileId = "f" + nextId.getAndIncrement();
        files.put(fileId, new StoredFile(fileName, content));
        return append(peerId, caption, null, fileId, fileName);
    }

    public void recordIncoming(String peerId, String text) {
        append(peerId, text, null, null, null, true);
    }

    public boolean markStarted(String peerId) {
        return started.add(peerId);
    }

    private MessageRef append(String peerId, String text, Keyboard keyboard, String fileId, String fileName) {
        return append(peerId, text, keyboard, fileId, fileName, false);
    }

    private MessageRef append(String peerId, String text, Keyboard keyboard, String fileId, String fileName, boolean mine) {
        var message = new ChatMessage("m" + nextId.getAndIncrement(), text, keyboard, fileId, fileName,
                mine, System.currentTimeMillis());
        List<ChatMessage> chat = chats.computeIfAbsent(peerId, id -> new ArrayList<>());
        synchronized (chat) {
            chat.add(message);
            if (chat.size() > MAX_MESSAGES_PER_CHAT) {
                chat.removeFirst();
            }
        }
        revision.incrementAndGet();
        return new MessageRef(peerId, message.id());
    }

    // старое сообщение могло вытесниться лимитом - тогда кидаем
    @Override
    public void editMessage(MessageRef message, String text, Keyboard keyboard) {
        List<ChatMessage> chat = chats.getOrDefault(message.peerId(), List.of());
        synchronized (chat) {
            for (int i = 0; i < chat.size(); i++) {
                ChatMessage old = chat.get(i);
                if (old.id().equals(message.messageId())) {
                    chat.set(i, new ChatMessage(old.id(), text, keyboard, old.fileId(), old.fileName(), old.mine(), old.sentAt()));
                    revision.incrementAndGet();
                    return;
                }
            }
        }
        throw new MessageDeliveryException("эмулятор: нет сообщения " + message.messageId() + " у " + message.peerId());
    }

    @Override
    public void answerCallback(CallbackRef callback, String text) {
        callbackAnswers.put(callback.eventId(), text == null ? "" : text);
    }

    public List<ChatMessage> chat(String peerId) {
        List<ChatMessage> chat = chats.getOrDefault(peerId, List.of());
        synchronized (chat) {
            return List.copyOf(chat);
        }
    }

    public Optional<String> takeCallbackAnswer(String eventId) {
        return Optional.ofNullable(callbackAnswers.remove(eventId));
    }

    public Optional<StoredFile> file(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    public long revision() {
        return revision.get();
    }
}
