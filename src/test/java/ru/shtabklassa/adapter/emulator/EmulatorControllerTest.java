package ru.shtabklassa.adapter.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.shtabklassa.model.ClassEntity;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.ClassRepository;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.service.AnnouncementService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ровно те запросы, что шлёт страница эмулятора
@SpringBootTest(properties = {
        "emulator.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:emulator-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmulatorControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ClassRepository classes;
    @Autowired UserRepository users;
    @Autowired AnnouncementService announcements;

    private JsonNode chat(String peer) throws Exception {
        return json.readTree(mvc.perform(get("/api/emulator/chats/" + peer)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode lastMessage(String peer) throws Exception {
        JsonNode messages = chat(peer).path("messages");
        return messages.get(messages.size() - 1);
    }

    @Test
    void teacherAndParentTalkThroughTheSameBotLogic() throws Exception {
        ClassEntity klass = classes.save(new ClassEntity("5 «В»"));
        User teacher = users.save(new User("emu-t", Role.TEACHER, klass.getId(), "Анна Сергеевна"));
        klass.setTeacherId(teacher.getId());
        classes.save(klass);
        users.save(new User("emu-p1", Role.PARENT, klass.getId(), "Ирина Петрова"));
        users.save(new User("emu-p2", Role.PARENT, klass.getId(), "Олег Смирнов"));

        mvc.perform(post("/api/emulator/chats/emu-t/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"привет\"}")).andExpect(status().isNoContent());
        JsonNode menu = lastMessage("emu-t").path("keyboard");
        assertThat(menu.path("inline").asBoolean()).isFalse();
        assertThat(menu.path("rows").get(0).get(0).path("label").asText()).isEqualTo("📢 Новое объявление");

        long id = announcements.publish("emu-t", "Завтра сменка", false).id();
        JsonNode parentMessage = lastMessage("emu-p1");
        JsonNode readButton = parentMessage.path("keyboard").path("rows").get(0).get(0);
        assertThat(readButton.path("callback").asBoolean()).isTrue();

        String answer = mvc.perform(post("/api/emulator/chats/emu-p1/press").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("messageId", parentMessage.path("id").asText())
                                .put("payload", readButton.path("payload").asText()).toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(answer).path("notification").asText()).isEqualTo("✓ Отмечено: прочитал");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(chat("emu-t").path("messages").toString())
                .contains("Прочитали 1 из 2"));
        assertThat(id).isPositive();
    }

    @Test
    void reportFileCanBeDownloaded() throws Exception {
        ClassEntity klass = classes.save(new ClassEntity("5 «Г»"));
        User teacher = users.save(new User("emu-t2", Role.TEACHER, klass.getId(), "Анна Сергеевна"));
        klass.setTeacherId(teacher.getId());
        classes.save(klass);
        long id = announcements.publish("emu-t2", "Сменка", false).id();
        JsonNode summary = chat("emu-t2").path("messages").get(0);
        JsonNode rows = summary.path("keyboard").path("rows");
        JsonNode reportButton = rows.get(rows.size() - 1).get(0);

        mvc.perform(post("/api/emulator/chats/emu-t2/press").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("messageId", summary.path("id").asText())
                        .put("payload", reportButton.path("payload").asText()).toString()));

        String fileId = lastMessage("emu-t2").path("fileId").asText();
        byte[] csv = mvc.perform(get("/api/emulator/files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("report-announcement-" + id)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv).startsWith(0xEF, 0xBB, 0xBF);
        mvc.perform(get("/api/emulator/files/nope")).andExpect(status().isNotFound());
    }

    @Test
    void startGreetsOnceAndOwnMessagesAreKept() throws Exception {
        ClassEntity klass = classes.save(new ClassEntity("5 «Е»"));
        User teacher = users.save(new User("emu-t4", Role.TEACHER, klass.getId(), "Анна Сергеевна Козлова"));
        klass.setTeacherId(teacher.getId());
        classes.save(klass);

        mvc.perform(post("/api/emulator/chats/emu-t4/start")).andExpect(status().isNoContent());
        mvc.perform(post("/api/emulator/chats/emu-t4/start")).andExpect(status().isNoContent());

        JsonNode messages = chat("emu-t4").path("messages");
        assertThat(messages).as("повторное открытие чата не здоровается второй раз").hasSize(1);
        assertThat(messages.get(0).path("text").asText()).startsWith("Здравствуйте, Анна Сергеевна!");
        assertThat(messages.get(0).path("mine").asBoolean()).isFalse();

        mvc.perform(post("/api/emulator/chats/emu-t4/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"как дела\"}"));
        JsonNode after = chat("emu-t4").path("messages");
        assertThat(after.get(1).path("text").asText()).isEqualTo("как дела");
        assertThat(after.get(1).path("mine").asBoolean()).isTrue();
        assertThat(after.get(2).path("mine").asBoolean()).isFalse();
    }

    @Test
    void peopleListHasOnlyTeachersAndLiveParents() throws Exception {
        ClassEntity klass = classes.save(new ClassEntity("5 «Д»"));
        users.save(new User("emu-t3", Role.TEACHER, klass.getId(), "Учитель"));
        users.save(new User("demo-09", Role.PARENT, klass.getId(), "Демо Родитель"));
        users.save(new User(null, Role.STUDENT, klass.getId(), "Ученик"));

        JsonNode people = json.readTree(mvc.perform(get("/api/emulator/people")).andReturn().getResponse().getContentAsString());

        assertThat(people.toString()).doesNotContain("Ученик").doesNotContain("demo-09");
        assertThat(people.findValues("id").toString()).contains("emu-t3");
    }
}
