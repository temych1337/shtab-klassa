package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/*
 * Первый клик правит сводку сразу. Дальше не чаще раза в minInterval, иначе 27 кликов = 27 edit и упираемся
 * в лимит VK (~20 rps). Всё что пришло в окне схлопывается в одну правку в конце, она читает бд
 * в момент отправки, так что последние цифры всегда верные.
 */
public class SummaryRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SummaryRefresher.class);

    private final Consumer<SummaryTarget> pushSummary;
    private final ScheduledExecutorService scheduler;
    private final long minIntervalMillis;
    private final Map<SummaryTarget, Slot> slots = new ConcurrentHashMap<>();

    private static final class Slot {
        boolean busy;
        boolean dirty;
    }

    public SummaryRefresher(Consumer<SummaryTarget> pushSummary, ScheduledExecutorService scheduler, Duration minInterval) {
        this.pushSummary = pushSummary;
        this.scheduler = scheduler;
        this.minIntervalMillis = minInterval.toMillis();
    }

    @TransactionalEventListener
    public void onAnswersChanged(AnswersChanged event) {
        requestRefresh(event.target());
    }

    public void requestRefresh(SummaryTarget target) {
        Slot slot = slots.computeIfAbsent(target, key -> new Slot());
        synchronized (slot) {
            if (slot.busy) {
                slot.dirty = true;
                return;
            }
            slot.busy = true;
        }
        scheduler.execute(() -> push(target, slot));
    }

    private void push(SummaryTarget target, Slot slot) {
        try {
            pushSummary.accept(target);
        } catch (RuntimeException e) {
            // не страшно, следующий клик перерисует
            log.warn("не обновилась сводка {}: {}", target, e.getMessage());
        }
        scheduler.schedule(() -> afterWindow(target, slot), minIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void afterWindow(SummaryTarget target, Slot slot) {
        synchronized (slot) {
            if (!slot.dirty) {
                slot.busy = false;
                return;
            }
            slot.dirty = false;
        }
        push(target, slot);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
