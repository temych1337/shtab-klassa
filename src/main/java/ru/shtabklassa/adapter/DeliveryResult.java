package ru.shtabklassa.adapter;

public record DeliveryResult(String peerId, MessageRef message, String error) {

    public static DeliveryResult delivered(MessageRef message) {
        return new DeliveryResult(message.peerId(), message, null);
    }

    public static DeliveryResult failed(String peerId, String error) {
        return new DeliveryResult(peerId, null, error);
    }

    public boolean isDelivered() {
        return message != null;
    }
}
