package ru.shtabklassa.adapter;

// payload - json который мы сами положили в кнопку (или null)
public sealed interface IncomingEvent {

    record TextMessage(String fromId, String peerId, String text, String payload) implements IncomingEvent {
    }

    // message может быть null если транспорт не сказал
    record ButtonCallback(CallbackRef callback, String payload, MessageRef message) implements IncomingEvent {
    }
}
