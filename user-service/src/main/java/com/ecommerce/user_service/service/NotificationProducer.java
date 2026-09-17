package com.ecommerce.user_service.service;

import com.ecommerce.user_service.payload.NotificationEmail;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${queue.notification.exchange}")
    private String notificationExchange;

    @Value("${queue.notification.routing-key}")
    private String notificationRoutingKey;

    public void sendRegistrationEmail(String email, String username) {
        NotificationEmail notificationEmail = NotificationEmail.builder()
                .recipient(email)
                .subject("Welcome to Laptop Ecommerce")
                .msgBody("Hi " + username + ", welcome to our store! Your account has been created successfully.")
                .build();

        rabbitTemplate.convertAndSend(notificationExchange, notificationRoutingKey, notificationEmail);
    }

    // sendPasswordChangedEmail was removed with ADR-0012. Passwords are changed in Keycloak's
    // account console, which this service never hears about; Keycloak's own SMTP settings
    // are where a "password changed" notice belongs now.
}