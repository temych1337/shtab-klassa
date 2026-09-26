package ru.shtabklassa.adapter.vk;

import ru.shtabklassa.adapter.MessageDeliveryException;

public class VkApiException extends MessageDeliveryException {

    private final int code;

    public VkApiException(String method, int code, String vkMessage) {
        super("VK " + method + " error " + code + ": " + vkMessage);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
