package ch.it4user.foodsharing.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender javaMailSender;
    private final AppProperties appProperties;
    private final OutboundMessageGuard outboundGuard;
    private final PushNotificationService pushNotificationService;

    public EmailService(ObjectProvider<JavaMailSender> javaMailSenderProvider, AppProperties appProperties,
                        OutboundMessageGuard outboundGuard, PushNotificationService pushNotificationService) {
        this.outboundGuard = outboundGuard;
        this.pushNotificationService = pushNotificationService;
        this.javaMailSender = javaMailSenderProvider.getIfAvailable();
        this.appProperties = appProperties;
    }

    /**
     * Also pushes the text to the user's phones with the app.
     * @param recipientFoodsharingId the Foodsharing user the email belongs to; checked against the outbound allowlist
     */
    public void send(String recipientFoodsharingId, String recipient, String subject, String body) {
        pushNotificationService.sendToUser(recipientFoodsharingId, subject, body);
        if (!outboundGuard.allowFoodsharingUser(recipientFoodsharingId, "email to " + recipient + ": " + subject)) {
            return;
        }
        if (javaMailSender == null) {
            log.info("Email to {} [{}]:\n{}", recipient, subject, body);
            return;
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(appProperties.getMail().getFrom());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            javaMailSender.send(message);
        } catch (Exception ex) {
            log.warn("SMTP unavailable, logging email to console instead: {}", ex.getMessage());
            log.info("Email to {} [{}]:\n{}", recipient, subject, body);
        }
    }

    /**
     * Also pushes the text to the user's phones with the app.
     * @param recipientFoodsharingId the Foodsharing user the email belongs to; checked against the outbound allowlist
     */
    public void send(String recipientFoodsharingId, String recipient, String subject, String plainTextBody, String htmlBody) {
        pushNotificationService.sendToUser(recipientFoodsharingId, subject, plainTextBody);
        if (!outboundGuard.allowFoodsharingUser(recipientFoodsharingId, "email to " + recipient + ": " + subject)) {
            return;
        }
        if (javaMailSender == null) {
            log.info("Email to {} [{}]:\n{}", recipient, subject, plainTextBody);
            return;
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(appProperties.getMail().getFrom());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(plainTextBody, htmlBody);
            javaMailSender.send(message);
        } catch (Exception ex) {
            log.warn("SMTP unavailable, logging email to console instead: {}", ex.getMessage());
            log.info("Email to {} [{}]:\n{}", recipient, subject, plainTextBody);
        }
    }
}
