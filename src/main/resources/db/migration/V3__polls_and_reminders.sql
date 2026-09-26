-- У опроса, как и у объявления, есть автор, которому показываем сводку.
-- NOT NULL без DEFAULT: к этой миграции опросов в базе ещё нет (создавать их можно только с фазы 3).
ALTER TABLE polls ADD COLUMN author_id BIGINT NOT NULL;
ALTER TABLE polls ADD CONSTRAINT fk_polls_author FOREIGN KEY (author_id) REFERENCES users (id);
ALTER TABLE polls ADD COLUMN summary_message_id BIGINT;

-- «Напомнить молчащим»: когда напоминали последний раз, чтобы двойной клик не слал вторую рассылку
ALTER TABLE polls ADD COLUMN last_reminded_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE announcements ADD COLUMN last_reminded_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE poll_deliveries (
    poll_id       BIGINT NOT NULL REFERENCES polls (id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users (id),
    message_id    BIGINT,
    failed_reason VARCHAR(500),
    sent_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (poll_id, user_id),
    CONSTRAINT ck_poll_delivery_either_sent_or_failed CHECK ((message_id IS NULL) <> (failed_reason IS NULL))
);
