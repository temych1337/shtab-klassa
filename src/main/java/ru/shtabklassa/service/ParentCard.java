package ru.shtabklassa.service;

import ru.shtabklassa.adapter.Keyboard;

// только свой ответ, чужих цифр родителю не показываем. keyboard null = кнопки убрать
public record ParentCard(String text, Keyboard keyboard) {
}
