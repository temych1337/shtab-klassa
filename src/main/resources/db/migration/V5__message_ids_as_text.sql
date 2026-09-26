-- В MAX id сообщения — строка («mid.…»), а не число, как conversation_message_id в VK.
ALTER TABLE announcements ALTER COLUMN summary_message_id SET DATA TYPE VARCHAR(128);
ALTER TABLE polls ALTER COLUMN summary_message_id SET DATA TYPE VARCHAR(128);
ALTER TABLE announcement_deliveries ALTER COLUMN message_id SET DATA TYPE VARCHAR(128);
ALTER TABLE poll_deliveries ALTER COLUMN message_id SET DATA TYPE VARCHAR(128);
