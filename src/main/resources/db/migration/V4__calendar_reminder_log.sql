-- Какие напоминания календаря уже ушли. Строка вставляется до отправки (ON CONFLICT DO NOTHING):
-- задача Quartz, сработавшая повторно или после рестарта, второе сообщение не пошлёт.
CREATE TABLE calendar_reminder_log (
    target_kind    VARCHAR(16) NOT NULL,
    target_id      BIGINT      NOT NULL,
    offset_minutes INT         NOT NULL,
    sent_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (target_kind, target_id, offset_minutes),
    CONSTRAINT ck_reminder_log_kind CHECK (target_kind IN ('EVENT', 'DUTY'))
);
