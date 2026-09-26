package ru.shtabklassa.adapter.emulator;

import org.junit.jupiter.api.Test;
import ru.shtabklassa.adapter.CallbackRef;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmulatorMessageSenderTest {

    private final EmulatorMessageSender emulator = new EmulatorMessageSender();

    @Test
    void editReplacesTextAndKeyboardInPlaceAndBumpsRevision() {
        MessageRef summary = emulator.sendKeyboard("t", "Прочитали 0 из 3",
                Keyboard.inline(List.of(Keyboard.Button.callback("🔔", "{}", Keyboard.Color.PRIMARY))));
        emulator.sendMessage("t", "другое");
        long before = emulator.revision();

        emulator.editMessage(summary, "Прочитали 1 из 3", null);

        assertThat(emulator.chat("t")).extracting(EmulatorMessageSender.ChatMessage::text)
                .containsExactly("Прочитали 1 из 3", "другое");
        assertThat(emulator.chat("t").getFirst().keyboard()).isNull();
        assertThat(emulator.revision()).isGreaterThan(before);
    }

    @Test
    void ownMessagesAreMarkedAndEditsKeepAuthorship() {
        emulator.recordIncoming("p", "привет");
        MessageRef reply = emulator.sendMessage("p", "ответ бота");
        emulator.editMessage(reply, "ответ бота, исправленный", null);

        assertThat(emulator.chat("p")).extracting(EmulatorMessageSender.ChatMessage::mine).containsExactly(true, false);
        assertThat(emulator.chat("p").getLast().sentAt()).isPositive();
    }

    @Test
    void chatIsStartedOnlyOnce() {
        assertThat(emulator.markStarted("p")).isTrue();
        assertThat(emulator.markStarted("p")).isFalse();
        assertThat(emulator.markStarted("t")).isTrue();
    }

    @Test
    void editOfUnknownMessageIsAnError() {
        assertThatThrownBy(() -> emulator.editMessage(new MessageRef("t", "m404"), "x", null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void callbackAnswerIsTakenOnce() {
        emulator.answerCallback(new CallbackRef("ev", "p", "p"), "✓ Отмечено");

        assertThat(emulator.takeCallbackAnswer("ev")).contains("✓ Отмечено");
        assertThat(emulator.takeCallbackAnswer("ev")).isEmpty();
    }

    @Test
    void documentIsStoredAndLinkedFromMessage() {
        emulator.sendDocument("t", "report.csv", new byte[]{1, 2}, "📥 Отчёт");

        EmulatorMessageSender.ChatMessage message = emulator.chat("t").getFirst();
        assertThat(message.fileName()).isEqualTo("report.csv");
        assertThat(emulator.file(message.fileId())).get().extracting(EmulatorMessageSender.StoredFile::content)
                .isEqualTo(new byte[]{1, 2});
    }

    @Test
    void chatKeepsOnlyRecentMessages() {
        for (int i = 0; i < EmulatorMessageSender.MAX_MESSAGES_PER_CHAT + 5; i++) {
            emulator.sendMessage("t", "#" + i);
        }

        assertThat(emulator.chat("t")).hasSize(EmulatorMessageSender.MAX_MESSAGES_PER_CHAT);
        assertThat(emulator.chat("t").getFirst().text()).isEqualTo("#5");
    }
}
