-- сообщение-сводка у учителя, которое редактируем на каждый ответ
ALTER TABLE announcements ADD COLUMN summary_message_id BIGINT;

-- кому ушло объявление и под каким id; failed_reason — чтобы показать учителю «не доставлено: N»
CREATE TABLE announcement_deliveries (
    announcement_id BIGINT NOT NULL REFERENCES announcements (id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    message_id      BIGINT,
    failed_reason   VARCHAR(500),
    sent_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (announcement_id, user_id),
    CONSTRAINT ck_delivery_either_sent_or_failed CHECK ((message_id IS NULL) <> (failed_reason IS NULL))
);
