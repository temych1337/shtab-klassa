package ru.shtabklassa.bot.handler;

import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.service.UserService;
import ru.shtabklassa.util.PersonName;

import java.util.Locale;
import java.util.Set;

// MAX шлёт /start, VK - "Начать", эмулятор /start сам
@Component
public class WelcomeHandler {

    private static final Set<String> START_WORDS = Set.of("/start", "начать", "старт");

    private final UserService users;
    private final MessageSender sender;

    public WelcomeHandler(UserService users, MessageSender sender) {
        this.users = users;
        this.sender = sender;
    }

    static boolean isStart(IncomingEvent.TextMessage message) {
        return message.text() != null && START_WORDS.contains(message.text().strip().toLowerCase(Locale.ROOT));
    }

    void welcome(User user, String peer) {
        UserService.ClassContext context = users.classContext(user).orElse(null);
        String className = context == null ? "" : " " + context.className();
        if (user.getRole() == Role.TEACHER) {
            sender.sendKeyboard(peer, "Здравствуйте, " + PersonName.address(user.getFullName()) + "! Это штаб класса"
                    + className + ": отсюда объявления и опросы уходят родителям, а сюда приходят живые сводки ответов.\n\n"
                    + "Всё — кнопками внизу.", Keyboards.teacherMenu());
            return;
        }
        String teacher = context == null || context.teacherName() == null
                ? "" : " Классный руководитель — " + context.teacherName() + ".";
        sender.sendKeyboard(peer, "Здравствуйте, " + PersonName.first(user.getFullName()) + "! Это бот класса"
                + className + "." + teacher + "\n\n"
                + "Сюда приходят объявления и опросы — отвечайте кнопками под ними, писать ничего не нужно. "
                + "Календарь класса — кнопкой ниже.", Keyboards.parentMenu());
    }
}
