package com.example.demo;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * Tokens for tests, signed by a key pair generated when the JVM starts.
 *
 * <p>No key is checked in, and that is deliberate: a private key in a repository is a finding in
 * every scanner ever written, and "it is only a test key" is exactly what the last person to commit
 * one said. Generating it costs a few milliseconds once.
 *
 * <p>The public half is written to a temp file and handed to Spring as
 * {@code public-key-location}, which replaces the {@code issuer-uri} the application uses in
 * production. That substitution is the only thing the tests change: the filter chain, the audience
 * check and the expiry check are the ones that ship.
 */
public final class TestTokens {

    public static final String AUDIENCE = "demo-api";
    public static final String ISSUER = "https://issuer.test/realms/demo";

    private static final KeyPair KEYS = generate();
    private static final Path PUBLIC_KEY_FILE = writePublicKey();

    private TestTokens() {}

    /** A token this service should accept. */
    public static String valid() {
        return signed(builder -> builder);
    }

    /** A token issued for a different service, which is the check people leave out. */
    public static String forAnotherAudience() {
        return signed(builder -> builder.audience("some-other-api"));
    }

    /** A token that was fine yesterday. */
    public static String expired() {
        return signed(builder -> builder.expirationTime(
                        java.util.Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .issueTime(java.util.Date.from(Instant.now().minus(2, ChronoUnit.HOURS))));
    }

    private static String signed(java.util.function.UnaryOperator<JWTClaimsSet.Builder> claims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject("integration-test")
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .issueTime(java.util.Date.from(Instant.now()))
                    .expirationTime(java.util.Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
            SignedJWT token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims.apply(builder).build());
            token.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return token.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Could not sign a test token", e);
        }
    }

    /** Where {@link LocalIssuerEnvironment} points Spring. Written once, per JVM. */
    static Path publicKeyFile() {
        return PUBLIC_KEY_FILE;
    }

    private static Path writePublicKey() {
        try {
            Path file = Files.createTempFile("kitbash-test-public-key", ".pem");
            file.toFile().deleteOnExit();
            String encoded = Base64.getMimeEncoder(64, System.lineSeparator().getBytes())
                    .encodeToString(((RSAPublicKey) KEYS.getPublic()).getEncoded());
            Files.write(file, List.of("-----BEGIN PUBLIC KEY-----", encoded, "-----END PUBLIC KEY-----"));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish the test public key", e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }
}
