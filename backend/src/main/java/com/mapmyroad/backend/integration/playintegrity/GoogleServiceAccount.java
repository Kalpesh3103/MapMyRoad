package com.mapmyroad.backend.integration.playintegrity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fields we need from a Google service-account key file ({@code client_email}, {@code private_key},
 * {@code private_key_id}). The file lives on the VM with restricted permissions (SECURITY.md section 10).
 */
public record GoogleServiceAccount(String clientEmail, String privateKeyId, RSAPrivateKey privateKey) {

    /** Loads and validates the key file. Error messages name the problem, never the file content. */
    public static GoogleServiceAccount load(Path file) {
        String json;
        try {
            json = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the Play Integrity service-account file", e);
        }
        return parse(json);
    }

    static GoogleServiceAccount parse(String json) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Play Integrity service-account file is not valid JSON");
        }
        String clientEmail = root.path("client_email").stringValue(null);
        String privateKeyPem = root.path("private_key").stringValue(null);
        String privateKeyId = root.path("private_key_id").stringValue(null);
        if (clientEmail == null || clientEmail.isBlank() || privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new IllegalStateException("Play Integrity service-account file lacks client_email or private_key");
        }
        return new GoogleServiceAccount(clientEmail, privateKeyId, parsePkcs8RsaKey(privateKeyPem));
    }

    /** Google key files carry an unencrypted PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}). */
    static RSAPrivateKey parsePkcs8RsaKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            // No cause attached: some providers echo key material in their messages.
            throw new IllegalStateException("Play Integrity service-account private_key is not a PKCS#8 RSA key");
        }
    }

    @Override
    public String toString() {
        return "GoogleServiceAccount[redacted]";
    }
}
