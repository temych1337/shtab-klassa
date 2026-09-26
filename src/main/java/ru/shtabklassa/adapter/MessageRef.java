package ru.shtabklassa.adapter;

// messageId строкой: в MAX mid.xxx, в VK conversation_message_id
public record MessageRef(String peerId, String messageId) {
}
