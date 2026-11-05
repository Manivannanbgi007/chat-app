package com.chatapp.message.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DeliveredRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> messageIds) {
}
