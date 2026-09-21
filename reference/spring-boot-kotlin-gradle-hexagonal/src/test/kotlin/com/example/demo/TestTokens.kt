package com.example.demo

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date

/**
 * Tokens for tests, signed by a key pair generated when the JVM starts.
 *
 * No key is checked in, and that is deliberate: a private key in a repository is a finding in every
 * scanner ever written, and "it is only a test key" is exactly what the last person to commit one
 * said. Generating it costs a few milliseconds once.
 *
 * The public half is written to a temp file and handed to Spring as `public-key-location`, which
 * replaces the `issuer-uri` the application uses in production. That substitution is the only thing
 * the tests change: the filter chain, the audience check and the expiry check are the ones that
 * ship.
 */
object TestTokens {
    const val AUDIENCE = "demo-api"
    const val ISSUER = "https://issuer.test/realms/demo"

    private val KEYS: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Where [LocalIssuerEnvironment] points Spring. Written once, per JVM. */
    val publicKeyFile: Path = writePublicKey()

    /** A token this service should accept. */
    fun valid(): String = signed { it }

    /** A token issued for a different service, which is the check people leave out. */
    fun forAnotherAudience(): String = signed { it.audience("some-other-api") }

    /** A token that was fine yesterday. */
    fun expired(): String =
        signed {
            it
                .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .issueTime(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
        }

    private fun signed(claims: (JWTClaimsSet.Builder) -> JWTClaimsSet.Builder): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject("integration-test")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)))
        val token =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                claims(builder).build(),
            )
        token.sign(RSASSASigner(KEYS.private as RSAPrivateKey))
        return token.serialize()
    }

    private fun writePublicKey(): Path {
        val file = Files.createTempFile("kitbash-test-public-key", ".pem")
        file.toFile().deleteOnExit()
        val encoded =
            Base64
                .getMimeEncoder(64, System.lineSeparator().toByteArray())
                .encodeToString((KEYS.public as RSAPublicKey).encoded)
        Files.write(file, listOf("-----BEGIN PUBLIC KEY-----", encoded, "-----END PUBLIC KEY-----"))
        return file
    }
}
