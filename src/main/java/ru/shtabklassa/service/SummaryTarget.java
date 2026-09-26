package ru.shtabklassa.service;

public record SummaryTarget(Kind kind, long id) {

    public enum Kind {
        ANNOUNCEMENT,
        POLL
    }

    public static SummaryTarget announcement(long id) {
        return new SummaryTarget(Kind.ANNOUNCEMENT, id);
    }

    public static SummaryTarget poll(long id) {
        return new SummaryTarget(Kind.POLL, id);
    }
}
