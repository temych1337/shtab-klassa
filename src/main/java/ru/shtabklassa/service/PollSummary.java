package ru.shtabklassa.service;

import ru.shtabklassa.model.PollType;

import java.util.List;

// counts пустой у CUSTOM, зато там recentAnswers (последние 3, полностью - в отчёте)
public record PollSummary(
        long pollId,
        String question,
        PollType type,
        long totalParents,
        long answered,
        List<Count> counts,
        List<String> recentAnswers,
        long undelivered,
        long demoAccounts) {

    public record Count(String label, long count) {
    }

    private static final int PREVIEW_LENGTH = 120;

    public long silent() {
        return Math.max(0, totalParents - answered);
    }

    public String render() {
        StringBuilder message = new StringBuilder("📋 ")
                .append(question.length() <= PREVIEW_LENGTH ? question : question.substring(0, PREVIEW_LENGTH - 1) + "…")
                .append("\n\n");
        if (totalParents > 0 && answered >= totalParents) {
            message.append("✅ ");
        }
        message.append("Ответили ").append(answered).append(" из ").append(totalParents);
        switch (type) {
            case CONSENT -> counts.forEach(count -> message.append(" · ").append(count.label()).append(": ").append(count.count()));
            case CHOICE -> counts.forEach(count -> message.append("\n• ").append(count.label()).append(" — ").append(count.count()));
            case CUSTOM -> {
                if (!recentAnswers.isEmpty()) {
                    message.append("\n\nПоследние:");
                    recentAnswers.forEach(answer -> message.append("\n").append(answer));
                }
            }
        }
        AnnouncementSummary.appendDelivery(message, undelivered, demoAccounts);
        return message.toString();
    }
}
