package org.monitoring.catchholebackend.global.config.emaildelivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.monitoring.catchholebackend.domain.auth.mail.SmtpEmailSender;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@DisplayName("로컬 SMTP 서버와 실제 STARTTLS 발송 통합")
class EmailDeliverySmtpIntegrationTest {

    private static SSLContext testTlsContext;

    @BeforeAll
    static void createLocalTestCertificate(@TempDir Path directory) throws Exception {
        Path keystorePath = directory.resolve("smtp-test.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "smtp-test", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keystorePath.toString(),
                "-storepass", "test-keystore-password", "-keypass", "test-keystore-password",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(keytool.waitFor(20, TimeUnit.SECONDS)).as("테스트 인증서 생성 종료").isTrue();
        assertThat(keytool.exitValue()).isZero();

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(keystorePath)) {
            keys.load(stream, "test-keystore-password".toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "test-keystore-password".toCharArray());
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("smtp-test", keys.getCertificate("smtp-test"));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        testTlsContext = SSLContext.getInstance("TLS");
        testTlsContext.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    }

    @Test
    @DisplayName("STARTTLS와 인증 뒤 로컬 서버가 실제 인증 메일 MIME을 수신한다")
    void deliversMessageOnlyAfterTlsAndAuthentication() throws Exception {
        try (LocalSmtpServer server = new LocalSmtpServer(true)) {
            JavaMailSenderImpl client = clientFor(server, true);

            new SmtpEmailSender(client, "sender@example.com")
                    .sendVerificationCode("recipient@example.com", "654321");
            SmtpConversation conversation = server.completed();

            assertThat(conversation.tlsNegotiated()).isTrue();
            assertThat(conversation.authenticated()).isTrue();
            assertThat(conversation.authenticationBeforeTls()).isFalse();
            MimeMessage received = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(conversation.message().getBytes(StandardCharsets.UTF_8)));
            assertThat(received.getRecipients(Message.RecipientType.TO))
                    .extracting(Object::toString).containsExactly("recipient@example.com");
            assertThat(received.getSubject()).isEqualTo("[캐치홀] 회원가입 이메일 인증번호");
            assertThat(received.getContent().toString()).contains("654321", "캐치홀");
        }
    }

    @Test
    @DisplayName("STARTTLS를 지원하지 않는 서버에는 자격 증명이나 메일을 보내지 않는다")
    void refusesPlaintextSmtpServer() throws Exception {
        try (LocalSmtpServer server = new LocalSmtpServer(false)) {
            JavaMailSenderImpl client = clientFor(server, true);

            assertThatThrownBy(() -> new SmtpEmailSender(client, "sender@example.com")
                    .sendVerificationCode("recipient@example.com", "654321"))
                    .isInstanceOf(AppException.class).hasNoCause();

            SmtpConversation conversation = server.completed();
            assertThat(conversation.authenticated()).isFalse();
            assertThat(conversation.message()).isEmpty();
        }
    }

    @Test
    @DisplayName("신뢰되지 않은 TLS 인증서는 인증과 메일 발송 전에 거절한다")
    void refusesUntrustedServerCertificate() throws Exception {
        try (LocalSmtpServer server = new LocalSmtpServer(true)) {
            JavaMailSenderImpl client = clientFor(server, false);

            assertThatThrownBy(() -> new SmtpEmailSender(client, "sender@example.com")
                    .sendVerificationCode("recipient@example.com", "654321"))
                    .isInstanceOf(AppException.class).hasNoCause();

            SmtpConversation conversation = server.completed();
            assertThat(conversation.authenticated()).isFalse();
            assertThat(conversation.message()).isEmpty();
        }
    }

    @Test
    @DisplayName("신뢰한 인증서라도 접속 호스트 이름이 다르면 인증과 메일 발송 전에 거절한다")
    void refusesServerHostnameMismatch() throws Exception {
        try (LocalSmtpServer server = new LocalSmtpServer(true)) {
            JavaMailSenderImpl client = clientFor(server, true);
            client.setHost("127.0.0.1");

            assertThatThrownBy(() -> new SmtpEmailSender(client, "sender@example.com")
                    .sendVerificationCode("recipient@example.com", "654321"))
                    .isInstanceOf(AppException.class).hasNoCause();

            SmtpConversation conversation = server.completed();
            assertThat(conversation.authenticated()).isFalse();
            assertThat(conversation.message()).isEmpty();
        }
    }

    private JavaMailSenderImpl clientFor(LocalSmtpServer server, boolean trustTestCertificate) {
        JavaMailSenderImpl client = new EmailDeliveryConfig().createSmtpMailSender(
                new EmailDeliveryProperties.Smtp("localhost", 587, "test-user", "test-password", "sender@example.com")
        );
        // 테스트만 임시 포트와 생성한 인증서 신뢰 루트를 사용한다. 운영 TLS 검증 설정은 그대로 둔다.
        client.setPort(server.port());
        if (trustTestCertificate) {
            client.getJavaMailProperties().put("mail.smtp.ssl.socketFactory", testTlsContext.getSocketFactory());
        }
        return client;
    }

    private record SmtpConversation(boolean tlsNegotiated, boolean authenticated,
                                    boolean authenticationBeforeTls, String message) {
    }

    private static final class LocalSmtpServer implements AutoCloseable {

        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        private final Future<SmtpConversation> conversation;

        private LocalSmtpServer(boolean supportsTls) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(10000);
            conversation = executor.submit(() -> receive(supportsTls));
        }

        int port() {
            return listener.getLocalPort();
        }

        SmtpConversation completed() throws Exception {
            return conversation.get(10, TimeUnit.SECONDS);
        }

        private SmtpConversation receive(boolean supportsTls) throws Exception {
            boolean tls = false;
            boolean authenticated = false;
            boolean authenticationBeforeTls = false;
            StringBuilder message = new StringBuilder();
            try (Socket connection = listener.accept()) {
                connection.setSoTimeout(10000);
                Socket socket = connection;
                BufferedReader reader = reader(socket);
                PrintWriter writer = writer(socket);
                respond(writer, "220 localhost test SMTP");
                String command;
                while ((command = reader.readLine()) != null) {
                    if (command.startsWith("EHLO")) {
                        respond(writer, "250-localhost");
                        if (supportsTls && !tls) {
                            respond(writer, "250-STARTTLS");
                        }
                        respond(writer, "250 AUTH PLAIN");
                    } else if (command.equals("STARTTLS")) {
                        respond(writer, "220 Ready to start TLS");
                        SSLSocket secured = (SSLSocket) testTlsContext.getSocketFactory()
                                .createSocket(socket, "localhost", socket.getPort(), true);
                        secured.setUseClientMode(false);
                        secured.setSoTimeout(10000);
                        try {
                            secured.startHandshake();
                        } catch (java.io.IOException exception) {
                            secured.close();
                            return new SmtpConversation(false, false, false, "");
                        }
                        socket = secured;
                        reader = reader(socket);
                        writer = writer(socket);
                        tls = true;
                    } else if (command.startsWith("AUTH PLAIN")) {
                        authenticated = true;
                        authenticationBeforeTls = !tls;
                        respond(writer, "235 Authentication successful");
                    } else if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")) {
                        respond(writer, "250 OK");
                    } else if (command.equals("DATA")) {
                        respond(writer, "354 End with a dot");
                        String line;
                        while ((line = reader.readLine()) != null && !line.equals(".")) {
                            message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        }
                        respond(writer, "250 Message accepted");
                    } else if (command.equals("QUIT")) {
                        respond(writer, "221 Bye");
                        break;
                    } else {
                        respond(writer, "500 Unsupported command");
                    }
                }
            }
            return new SmtpConversation(tls, authenticated, authenticationBeforeTls, message.toString());
        }

        private BufferedReader reader(Socket socket) throws Exception {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        private PrintWriter writer(Socket socket) throws Exception {
            return new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void respond(PrintWriter writer, String response) {
            writer.print(response + "\r\n");
            writer.flush();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
        }
    }
}
