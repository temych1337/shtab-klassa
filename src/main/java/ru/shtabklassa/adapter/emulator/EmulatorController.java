package ru.shtabklassa.adapter.emulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.shtabklassa.adapter.CallbackRef;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.bot.handler.UpdateDispatcher;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.service.ParentBroadcaster;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// страница шлёт сюда текст и нажатия, дальше тот же UpdateDispatcher что и у MAX.
// авторизации нет, только локально!
@RestController
@RequestMapping("/api/emulator")
@ConditionalOnProperty(name = "emulator.enabled", havingValue = "true")
public class EmulatorController {

    public record Person(String id, String name, String role) {
    }

    public record Chat(long revision, List<EmulatorMessageSender.ChatMessage> messages) {
    }

    public record TextRequest(String text) {
    }

    public record PressRequest(String messageId, String payload) {
    }

    public record PressResponse(String notification) {
    }

    private final EmulatorMessageSender emulator;
    private final UpdateDispatcher dispatcher;
    private final UserRepository users;

    public EmulatorController(EmulatorMessageSender emulator, UpdateDispatcher dispatcher, UserRepository users) {
        this.emulator = emulator;
        this.dispatcher = dispatcher;
        this.users = users;
    }

    // учителя и живые родители, demo-NN и учеников не показываем
    @GetMapping("/people")
    public List<Person> people() {
        return users.findAll().stream()
                .filter(user -> user.getRole() != Role.STUDENT && user.getExternalId() != null
                        && !user.getExternalId().startsWith(ParentBroadcaster.DEMO_PREFIX))
                .sorted(Comparator.comparing((User user) -> user.getRole() != Role.TEACHER).thenComparing(User::getFullName))
                .map(user -> new Person(user.getExternalId(), user.getFullName(), user.getRole().name()))
                .toList();
    }

    @PostMapping("/chats/{peerId}/start")
    public ResponseEntity<Void> start(@PathVariable String peerId) {
        if (emulator.markStarted(peerId)) {
            dispatcher.onEvent(new IncomingEvent.TextMessage(peerId, peerId, "/start", null));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chats/{peerId}")
    public Chat chat(@PathVariable String peerId) {
        // ревизия до чтения - если что-то влезет между, страница просто перерисует ещё раз
        long revision = emulator.revision();
        return new Chat(revision, emulator.chat(peerId));
    }

    @PostMapping("/chats/{peerId}/messages")
    public ResponseEntity<Void> write(@PathVariable String peerId, @RequestBody TextRequest request) {
        emulator.recordIncoming(peerId, request.text());
        dispatcher.onEvent(new IncomingEvent.TextMessage(peerId, peerId, request.text(), null));
        return ResponseEntity.noContent().build();
    }

    // синхронно, поэтому всплывашку можно сразу вернуть в ответе
    @PostMapping("/chats/{peerId}/press")
    public PressResponse press(@PathVariable String peerId, @RequestBody PressRequest request) {
        String eventId = UUID.randomUUID().toString();
        dispatcher.onEvent(new IncomingEvent.ButtonCallback(new CallbackRef(eventId, peerId, peerId),
                request.payload(), new MessageRef(peerId, request.messageId())));
        return new PressResponse(emulator.takeCallbackAnswer(eventId).orElse(null));
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable String fileId) {
        return emulator.file(fileId)
                .map(file -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                        .body(file.content()))
                .orElse(ResponseEntity.notFound().build());
    }
}
