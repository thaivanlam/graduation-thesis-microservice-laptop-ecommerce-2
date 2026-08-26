package vn.vti.dtn2504.notificationservice.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import vn.vti.dtn2504.notificationservice.payload.EmailDetails;
import vn.vti.dtn2504.notificationservice.service.EmailServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailServiceImpl}, the last hop of the notification chain. The mail
 * sender is a test double, so no SMTP connection is ever attempted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit - EmailServiceImpl")
class EmailServiceImplTest {

    private static final String SENDER = "no-reply@techzone.test";

    @Mock
    private JavaMailSender javaMailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    @BeforeEach
    void injectSenderAddress() {
        ReflectionTestUtils.setField(emailService, "sender", SENDER);
    }

    private static EmailDetails details() {
        return new EmailDetails("buyer@techzone.test",
                "Thank you for your purchase! Your order 500 has been placed successfully.",
                "Order Confirmation - Order 500");
    }

    @Test
    @DisplayName("builds the message from the configured sender and the requested recipient")
    void buildsMessage() {
        emailService.sendSimpleMail(details());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo(SENDER);
        assertThat(message.getTo()).containsExactly("buyer@techzone.test");
        assertThat(message.getSubject()).isEqualTo("Order Confirmation - Order 500");
        assertThat(message.getText()).contains("order 500");
    }

    @Test
    @DisplayName("BUG-06 characterisation: a failed send is swallowed and the message is lost")
    void swallowsSendFailure() {
        // The catch block prints "Mail Failed" and returns normally, so the RabbitMQ
        // listener acknowledges the message and the notification disappears with no retry
        // and no dead-letter queue. Documented as BUG-06 in docs/backend/known-defects.md.
        //
        // Pinned as current behaviour: once the failure is rethrown so the broker can
        // redeliver, this expectation must become assertThatThrownBy(...).
        doThrow(new MailSendException("SMTP host unreachable"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> emailService.sendSimpleMail(details())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null recipient is handed to the mail sender rather than rejected up front")
    void doesNotValidateRecipient() {
        EmailDetails withoutRecipient = new EmailDetails(null, "body", "subject");

        assertThatCode(() -> emailService.sendSimpleMail(withoutRecipient)).doesNotThrowAnyException();
    }
}
