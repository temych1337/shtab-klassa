package ru.shtabklassa.adapter.max;

import ru.shtabklassa.adapter.MessageDeliveryException;

public class MaxApiException extends MessageDeliveryException {

    private final int status;
    private final String code;

    public MaxApiException(String call, int status, String code, String maxMessage) {
        super("MAX " + call + " → " + status + " " + code + ": " + maxMessage);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
