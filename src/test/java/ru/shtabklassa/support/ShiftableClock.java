package ru.shtabklassa.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

// реальное время + сдвиг, чтобы "прошло 11 минут" без sleep
public class ShiftableClock extends Clock {

    private final AtomicReference<Duration> shift = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration by) {
        shift.updateAndGet(current -> current.plus(by));
    }

    public void reset() {
        shift.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(shift.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    // SchoolTime через это берёт "сегодня по Москве"
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(Clock.system(zone), shift.get());
    }
}
