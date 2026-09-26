package ru.shtabklassa.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// когда транспорт не включён - просто лог, чтобы поднималось без токена
public class LoggingMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageSender.class);

    private final AtomicLong nextMessageId = new AtomicLong(1);

    @Override
    public MessageRef sendMessage(String peerId, String text) {
        return sendKeyboard(peerId, text, null);
    }

    @Override
    public MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard) {
        MessageRef ref = new MessageRef(peerId, Long.toString(nextMessageId.getAndIncrement()));
        log.info("[no transport] -> {} #{}: {}", peerId, ref.messageId(), text);
        return ref;
    }

    @Override
    public List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard) {
        return peerIds.stream()
                .map(peerId -> DeliveryResult.delivered(sendKeyboard(peerId, text, keyboard)))
                .toList();
    }

    @Override
    public MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption) {
        MessageRef ref = new MessageRef(peerId, Long.toString(nextMessageId.getAndIncrement()));
        log.info("[no transport] -> {} #{}: файл {} ({} байт), {}", peerId, ref.messageId(), fileName, content.length, caption);
        return ref;
    }

    @Override
    public void editMessage(MessageRef message, String text, Keyboard keyboard) {
        log.info("[no transport] edit {} #{}: {}", message.peerId(), message.messageId(), text);
    }

    @Override
    public void answerCallback(CallbackRef callback, String text) {
        log.info("[no transport] snackbar -> {}: {}", callback.userId(), text);
    }
}
