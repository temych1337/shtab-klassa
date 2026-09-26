package ru.shtabklassa.adapter;

import java.util.List;

// inline = под сообщением, иначе меню внизу чата
public record Keyboard(List<List<Button>> rows, boolean inline) {

    @SafeVarargs
    public static Keyboard inline(List<Button>... rows) {
        return new Keyboard(List.of(rows), true);
    }

    @SafeVarargs
    public static Keyboard menu(List<Button>... rows) {
        return new Keyboard(List.of(rows), false);
    }

    // callback=false -> кнопка просто шлёт свой label текстом
    public record Button(String label, String payload, Color color, boolean callback) {

        public static Button callback(String label, String payload, Color color) {
            return new Button(label, payload, color, true);
        }

        public static Button text(String label, String payload, Color color) {
            return new Button(label, payload, color, false);
        }
    }

    public enum Color {
        PRIMARY,
        SECONDARY,
        POSITIVE,
        NEGATIVE
    }
}
