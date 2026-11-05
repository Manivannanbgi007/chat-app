package com.chatapp.message.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.message.enums.ReceiptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** "This device of this recipient has received (and maybe read) this message." */
@Entity
@Table(name = "message_receipts")
@Getter
@Setter
@NoArgsConstructor
public class MessageReceipt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    /** The login session id of the device (the "sid" claim of its access token). */
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceiptStatus status;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;
}
