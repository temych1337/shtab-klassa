package ru.shtabklassa.service;

public record PublishResult(long id, int delivered, int failed, int demo) {

    static PublishResult of(long id, ParentBroadcaster.Tally tally) {
        return new PublishResult(id, tally.delivered(), tally.failed(), tally.demo());
    }
}
