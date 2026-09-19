package com.rollbackshield.connectors.github.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints the short-lived RS256 JWT GitHub App authentication requires, and
 * converts PKCS#1 ("BEGIN RSA PRIVATE KEY", what GitHub hands out) or PKCS#8
 * keys into a {@link PrivateKey}. Nothing here is persisted.
 */
public final class GitHubAppJwt {

    private GitHubAppJwt() {
    }

    /** JWT valid for 9 minutes, 60s in the past to tolerate clock skew. */
    public static String create(String appId, String privateKeyPem) {
        Instant now = Instant.now();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iat\":" + (now.getEpochSecond() - 60)
            + ",\"exp\":" + (now.getEpochSecond() + 540)
            + ",\"iss\":\"" + appId + "\"}";
        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8))
            + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(parsePrivateKey(privateKeyPem));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("Unable to sign GitHub App JWT: " + e.getMessage(), e);
        }
    }

    public static PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n").trim();
            if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
                byte[] der = decodePemBody(normalized, "RSA PRIVATE KEY");
                return KeyFactory.getInstance("RSA").generatePrivate(pkcs1ToSpec(der));
            }
            if (normalized.contains("BEGIN PRIVATE KEY")) {
                byte[] der = decodePemBody(normalized, "PRIVATE KEY");
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            throw new IllegalArgumentException("Unsupported private key format: expected PKCS#1 or PKCS#8 PEM");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("Unable to parse GitHub App private key: " + e.getMessage(), e);
        }
    }

    public static boolean isAppCredential(CredentialMaterial material) {
        return material instanceof CredentialMaterial.GitHubAppCredentials;
    }

    private static byte[] decodePemBody(String pem, String label) {
        String body = pem
            .replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /**
     * Minimal DER reader for the PKCS#1 RSAPrivateKey structure (RFC 8017):
     * SEQUENCE { version, modulus, publicExponent, privateExponent, ... }.
     */
    private static RSAPrivateCrtKeySpec pkcs1ToSpec(byte[] der) {
        DerReader reader = new DerReader(der);
        DerReader sequence = reader.readSequence();
        sequence.readInteger(); // version
        java.math.BigInteger modulus = sequence.readInteger();
        java.math.BigInteger publicExponent = sequence.readInteger();
        java.math.BigInteger privateExponent = sequence.readInteger();
        java.math.BigInteger primeP = sequence.readInteger();
        java.math.BigInteger primeQ = sequence.readInteger();
        java.math.BigInteger exponentP = sequence.readInteger();
        java.math.BigInteger exponentQ = sequence.readInteger();
        java.math.BigInteger coefficient = sequence.readInteger();
        return new RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, primeP, primeQ,
            exponentP, exponentQ, coefficient);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class DerReader {
        private final byte[] data;
        private int offset;

        DerReader(byte[] data) {
            this.data = data;
        }

        DerReader readSequence() {
            expect(0x30);
            int length = readLength();
            DerReader nested = new DerReader(java.util.Arrays.copyOfRange(data, offset, offset + length));
            offset += length;
            return nested;
        }

        java.math.BigInteger readInteger() {
            expect(0x02);
            int length = readLength();
            byte[] value = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return new java.math.BigInteger(value);
        }

        private void expect(int tag) {
            if (offset >= data.length || (data[offset] & 0xFF) != tag) {
                throw new IllegalArgumentException("Malformed DER: expected tag " + tag);
            }
            offset++;
        }

        private int readLength() {
            int first = data[offset++] & 0xFF;
            if ((first & 0x80) == 0) {
                return first;
            }
            int byteCount = first & 0x7F;
            int length = 0;
            for (int i = 0; i < byteCount; i++) {
                length = (length << 8) | (data[offset++] & 0xFF);
            }
            return length;
        }
    }
}
