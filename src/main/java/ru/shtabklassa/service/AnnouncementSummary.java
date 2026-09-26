package ru.shtabklassa.service;

// totalParents - весь класс, а не кому дошло: учителю нужно "из 27" даже если троим не доставлено
public record AnnouncementSummary(
        long announcementId,
        String text,
        boolean requiresDecision,
        long totalParents,
        long answered,
        long agreed,
        long declined,
        long undelivered,
        long demoAccounts) {

    private static final int PREVIEW_LENGTH = 120;

    public long silent() {
        return Math.max(0, totalParents - answered);
    }

    public String render() {
        StringBuilder message = new StringBuilder("📢 ").append(preview()).append("\n\n");
        if (totalParents > 0 && answered >= totalParents) {
            message.append("✅ ");
        }
        if (requiresDecision) {
            message.append("Ответили ").append(answered).append(" из ").append(totalParents)
                    .append(" · Согласны: ").append(agreed)
                    .append(" · Не смогут: ").append(declined);
        } else {
            message.append("Прочитали ").append(answered).append(" из ").append(totalParents);
        }
        appendDelivery(message, undelivered, demoAccounts);
        return message.toString();
    }

    // "Не доставлено: 0" пугало, поэтому только когда >0. демо отдельно - не ошибка, но и не доставлено
    static void appendDelivery(StringBuilder message, long undelivered, long demoAccounts) {
        if (undelivered > 0) {
            message.append("\n⚠ Не доставлено: ").append(undelivered);
        }
        if (demoAccounts > 0) {
            message.append("\nℹ️ Демо-аккаунтов: ").append(demoAccounts);
        }
    }

    private String preview() {
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH - 1) + "…";
    }
}
