package ru.shtabklassa.adapter;

import java.util.List;

// сервисы знают только это. peerId = User.externalId
// всё кроме рассылки списку кидает MessageDeliveryException
public interface MessageSender {

    MessageRef sendMessage(String peerId, String text);

    MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard);

    // не кидает даже если упало всё - результат по каждому, чтобы показать "не доставлено: N"
    List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard);

    MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption);

    // keyboard null = убрать кнопки
    void editMessage(MessageRef message, String text, Keyboard keyboard);

    // звать на КАЖДОЕ нажатие, иначе крутится индикатор. text null = просто погасить
    void answerCallback(CallbackRef callback, String text);
}
