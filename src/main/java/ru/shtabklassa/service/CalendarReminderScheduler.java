package ru.shtabklassa.service;

import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.shtabklassa.model.CalendarEvent;
import ru.shtabklassa.model.DutySchedule;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.repository.ReminderLogRepository.TargetKind;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

// Quartz в памяти, поэтому на старте всё будущее пересоздаём из бд. что выпало на простой - теряется.
// ключ задачи (что, id, offset) + replace=true, дублей не будет
@Service
public class CalendarReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderScheduler.class);

    static final String GROUP = "calendar-reminders";

    private final Scheduler scheduler;
    private final CalendarEventRepository events;
    private final DutyScheduleRepository duties;
    private final Clock clock;

    public CalendarReminderScheduler(Scheduler scheduler, CalendarEventRepository events,
                                     DutyScheduleRepository duties, Clock clock) {
        this.scheduler = scheduler;
        this.events = events;
        this.duties = duties;
        this.clock = clock;
    }

    public int scheduleEvent(CalendarEvent event) {
        List<ReminderPlan.Trigger> triggers = ReminderPlan.triggers(event.getStartAt(), event.getReminderOffsets(), clock.instant());
        triggers.forEach(trigger -> schedule(TargetKind.EVENT, event.getId(), trigger));
        return triggers.size();
    }

    public int scheduleDuty(DutySchedule duty) {
        List<ReminderPlan.Trigger> triggers = ReminderPlan.dutyTriggers(duty.getDate(), clock.instant());
        triggers.forEach(trigger -> schedule(TargetKind.DUTY, duty.getId(), trigger));
        return triggers.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public int rescheduleAll() {
        Instant now = clock.instant();
        int scheduled = 0;
        for (CalendarEvent event : events.findByStartAtAfter(now)) {
            scheduled += scheduleEvent(event);
        }
        for (DutySchedule duty : duties.findByDateGreaterThanEqual(SchoolTime.today(clock))) {
            scheduled += scheduleDuty(duty);
        }
        log.info("напоминания календаря пересозданы: {}", scheduled);
        return scheduled;
    }

    static JobKey jobKey(TargetKind kind, long targetId, int offsetMinutes) {
        return JobKey.jobKey(kind.name().toLowerCase() + "-" + targetId + "-" + offsetMinutes, GROUP);
    }

    private void schedule(TargetKind kind, long targetId, ReminderPlan.Trigger trigger) {
        JobKey key = jobKey(kind, targetId, trigger.offsetMinutes());
        JobDetail job = JobBuilder.newJob(CalendarReminderJob.class)
                .withIdentity(key)
                .usingJobData(CalendarReminderJob.KIND, kind.name())
                .usingJobData(CalendarReminderJob.TARGET_ID, targetId)
                .usingJobData(CalendarReminderJob.OFFSET, trigger.offsetMinutes())
                .build();
        Trigger quartzTrigger = TriggerBuilder.newTrigger()
                .withIdentity(key.getName(), GROUP)
                .startAt(Date.from(trigger.fireAt()))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();
        try {
            scheduler.scheduleJob(job, Set.of(quartzTrigger), true);
        } catch (SchedulerException e) {
            // RAMJobStore падает тут только если планировщик остановлен, т.е. конфиг сломан
            throw new IllegalStateException("не удалось поставить напоминание " + key, e);
        }
    }
}
