package ru.shtabklassa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.shtabklassa.adapter.DeliveryResult;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * Рассылка родителям с итогом по каждому (объявления, опросы, напоминания).
 * demo-NN (только shtab.demo-accounts=true, профиль seed) - выдуманные родители, в мессенджере их нет.
 * им ничего не шлём, пишем DEMO_REASON, сводка их считает отдельно от сбоев.
 */
@Component
public class ParentBroadcaster {

    public static final String DEMO_PREFIX = "demo-";
    public static final String DEMO_REASON = "демо-аккаунт, не в мессенджере";

    public record Outcome(User recipient, String messageId, String failedReason) {
        public boolean delivered() {
            return messageId != null;
        }

        public boolean demo() {
            return DEMO_REASON.equals(failedReason);
        }
    }

    // failed без демо
    public record Tally(int delivered, int failed, int demo) {
        public static Tally of(List<Outcome> outcomes) {
            int delivered = 0;
            int demo = 0;
            for (Outcome outcome : outcomes) {
                if (outcome.delivered()) {
                    delivered++;
                } else if (outcome.demo()) {
                    demo++;
                }
            }
            return new Tally(delivered, outcomes.size() - delivered - demo, demo);
        }
    }

    private final MessageSender sender;
    private final boolean demoAccounts;

    public ParentBroadcaster(MessageSender sender, @Value("${shtab.demo-accounts:false}") boolean demoAccounts) {
        this.sender = sender;
        this.demoAccounts = demoAccounts;
    }

    public List<Outcome> send(List<User> recipients, String text, Keyboard keyboard) {
        List<Outcome> outcomes = new ArrayList<>(recipients.size());
        List<User> reachable = new ArrayList<>();
        for (User recipient : recipients) {
            if (recipient.getExternalId() == null) {
                outcomes.add(new Outcome(recipient, null, "нет аккаунта"));
            } else if (demoAccounts && recipient.getExternalId().startsWith(DEMO_PREFIX)) {
                outcomes.add(new Outcome(recipient, null, DEMO_REASON));
            } else {
                reachable.add(recipient);
            }
        }
        if (reachable.isEmpty()) {
            return outcomes;
        }

        Map<String, User> byPeer = reachable.stream().collect(Collectors.toMap(User::getExternalId, Function.identity()));
        List<DeliveryResult> results = sender.sendKeyboard(reachable.stream().map(User::getExternalId).toList(), text, keyboard);
        for (DeliveryResult result : results) {
            User recipient = byPeer.get(result.peerId());
            outcomes.add(result.isDelivered()
                    ? new Outcome(recipient, result.message().messageId(), null)
                    : new Outcome(recipient, null, result.error()));
        }
        return outcomes;
    }
}
