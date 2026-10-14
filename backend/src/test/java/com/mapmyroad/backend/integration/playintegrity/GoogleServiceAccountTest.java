package com.mapmyroad.backend.integration.playintegrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleServiceAccountTest {

    private static String pkcs8Pem() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPrivate().getEncoded(); // PKCS#8
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void parsesServiceAccountJson() throws Exception {
        String json = """
                {"type": "service_account", "client_email": "sa@project.iam.gserviceaccount.com",
                 "private_key_id": "abc123", "private_key": "%s"}
                """.formatted(pkcs8Pem().replace("\n", "\\n"));

        GoogleServiceAccount account = GoogleServiceAccount.parse(json);

        assertThat(account.clientEmail()).isEqualTo("sa@project.iam.gserviceaccount.com");
        assertThat(account.privateKeyId()).isEqualTo("abc123");
        assertThat(account.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(account.toString()).doesNotContain("abc123");
    }

    @Test
    void rejectsMissingFieldsAndBadKeys() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse("{\"client_email\": \"x\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GoogleServiceAccount.parse("not json")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GoogleServiceAccount.parsePkcs8RsaKey("-----BEGIN PRIVATE KEY-----AAAA"))
                .isInstanceOf(IllegalStateException.class);
    }
}
