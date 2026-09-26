package ru.shtabklassa.service;

import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import ru.shtabklassa.repository.ReminderLogRepository.TargetKind;

public class CalendarReminderJob implements Job {

    static final String KIND = "kind";
    static final String TARGET_ID = "targetId";
    static final String OFFSET = "offsetMinutes";

    private final CalendarReminderSender reminders;

    public CalendarReminderJob(CalendarReminderSender reminders) {
        this.reminders = reminders;
    }

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        long targetId = data.getLong(TARGET_ID);
        int offset = data.getInt(OFFSET);
        switch (TargetKind.valueOf(data.getString(KIND))) {
            case EVENT -> reminders.sendEventReminder(targetId, offset);
            case DUTY -> reminders.sendDutyReminder(targetId, offset);
        }
    }
}
