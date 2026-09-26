package ru.shtabklassa.service;

// текст уходит пользователю как есть, так что коротко (всплывашка VK режет на 90 символах)
public abstract class DomainException extends RuntimeException {

    protected DomainException(String userMessage) {
        super(userMessage);
    }

    public static class UnknownUser extends DomainException {
        public UnknownUser() {
            super("Вас нет в списке класса. Напишите классному руководителю.");
        }
    }

    public static class NotAllowed extends DomainException {
        public NotAllowed(String userMessage) {
            super(userMessage);
        }
    }

    public static class NotFound extends DomainException {
        public NotFound(String userMessage) {
            super(userMessage);
        }
    }

    public static class InvalidInput extends DomainException {
        public InvalidInput(String userMessage) {
            super(userMessage);
        }
    }

    public static class TooSoon extends DomainException {
        public TooSoon(String userMessage) {
            super(userMessage);
        }
    }
}
