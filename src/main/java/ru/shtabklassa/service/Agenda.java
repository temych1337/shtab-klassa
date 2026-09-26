package ru.shtabklassa.service;

import java.time.Instant;
import java.util.List;

public record Agenda(int days, List<Entry> entries) {

    public record Entry(Instant at, String line) {
    }

    public String render() {
        if (entries.isEmpty()) {
            return "📅 Ближайшие " + days + " дней ничего не запланировано.";
        }
        StringBuilder text = new StringBuilder("📅 Ближайшие " + days + " дней:");
        entries.forEach(entry -> text.append("\n").append(entry.line()));
        return text.toString();
    }
}
