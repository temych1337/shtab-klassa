package ru.shtabklassa.bot.filter;

import org.springframework.stereotype.Component;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.UserService;

import java.util.Optional;

// с чужими бот не разговаривает
@Component
public class KnownUserFilter {

    public static final String UNKNOWN_USER_REPLY = "Вас нет в списке класса. Напишите классному руководителю.";

    private final UserService users;

    public KnownUserFilter(UserService users) {
        this.users = users;
    }

    public Optional<User> resolve(String externalId) {
        return users.findByExternalId(externalId);
    }
}
