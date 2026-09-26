package ru.shtabklassa.adapter;

@FunctionalInterface
public interface IncomingEventListener {

    void onEvent(IncomingEvent event);
}
