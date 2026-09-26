package ru.shtabklassa.bot.keyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

// имена полей в одну букву не просто так - VK режет payload на 255 символах. o = номер варианта
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Payload(Action a, Long id, Integer o) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public enum Action {
        ANN_READ,
        ANN_AGREE,
        ANN_DECLINE,
        NEW_ANN,
        MODE_READ,
        MODE_DECISION,
        DRAFT_SEND,
        DRAFT_CANCEL,
        NEW_POLL,
        PTYPE_CONSENT,
        PTYPE_CHOICE,
        PTYPE_CUSTOM,
        POLL_YES,
        POLL_NO,
        POLL_OPT,
        POLL_TEXT,
        REMIND_ANN,
        REMIND_POLL,
        CAL_VIEW,
        DUTY_VIEW,
        EVT_NEW,
        ETYPE_MEETING,
        ETYPE_EVENT,
        DUTY_PLAN,
        REPORT_ANN,
        REPORT_POLL,
        SILENT_ANN,
        SILENT_POLL
    }

    public static Payload of(Action action) {
        return new Payload(action, null, null);
    }

    public static Payload of(Action action, long id) {
        return new Payload(action, id, null);
    }

    public static Payload option(long pollId, int option) {
        return new Payload(Action.POLL_OPT, pollId, option);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // пусто = кнопка устарела
    public static Optional<Payload> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Payload payload = JSON.readValue(json, Payload.class);
            return Optional.ofNullable(payload.a() == null ? null : payload);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
