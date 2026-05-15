package com.circleguard.auth.service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-1: QrTokenService — Validación de expiración de tokens QR
 *
 * Objetivo: Verificar que un token generado con TTL mínimo es rechazado
 * tras expirar, y que un token dentro del TTL es aceptado correctamente.
 *
 * Criticidad: ALTA — el QR de 60s es el único mecanismo de control de acceso
 * físico al campus. Una falla aquí permite entrada a usuarios suspendidos.
 */
@DisplayName("UT-1: QrTokenService — Token Expiration Validation")
class QrTokenServiceTest {

    private static final String TEST_SECRET = "my-qr-secret-key-for-dev-1234567890ab"; // 32+ chars
    private Key key;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
    }

    @Test
    @DisplayName("Token válido dentro del TTL debe parsearse sin excepción")
    void validToken_withinTtl_shouldParseSuccessfully() {
        // Arrange — TTL de 5 segundos (más que suficiente para el test)
        QrTokenService service = new QrTokenService(TEST_SECRET, 5000L);
        UUID anonymousId = UUID.randomUUID();

        // Act
        String token = service.generateQrToken(anonymousId);

        // Assert — debe parsear correctamente y extraer el subject
        assertDoesNotThrow(() -> {
            String subject = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
            assertEquals(anonymousId.toString(), subject,
                    "El subject del token debe ser el anonymousId del usuario");
        });
    }

    @Test
    @DisplayName("Token expirado debe lanzar ExpiredJwtException")
    void expiredToken_shouldThrowExpiredJwtException() throws InterruptedException {
        // Arrange — TTL de 1ms (expira inmediatamente)
        QrTokenService service = new QrTokenService(TEST_SECRET, 1L);
        UUID anonymousId = UUID.randomUUID();

        // Act
        String token = service.generateQrToken(anonymousId);
        Thread.sleep(10); // Esperar que expire

        // Assert — token expirado debe rechazarse
        assertThrows(ExpiredJwtException.class, () ->
                Jwts.parserBuilder()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token),
                "Un token expirado DEBE lanzar ExpiredJwtException para bloquear el acceso"
        );
    }

    @Test
    @DisplayName("Token generado debe contener anonymousId como subject")
    void generatedToken_shouldContainAnonymousIdAsSubject() {
        // Arrange
        QrTokenService service = new QrTokenService(TEST_SECRET, 60000L);
        UUID anonymousId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // Act
        String token = service.generateQrToken(anonymousId);

        // Assert
        String subject = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();

        assertEquals("550e8400-e29b-41d4-a716-446655440000", subject);
    }

    @Test
    @DisplayName("Dos tokens para el mismo usuario con distinto TTL deben ser distintos")
    void twoTokens_withDifferentExpiration_shouldBeDifferent() {
        // JWT issuedAt tiene precisión de segundos — usamos TTLs distintos para garantizar diferencia
        QrTokenService service1 = new QrTokenService(TEST_SECRET, 60000L);
        QrTokenService service2 = new QrTokenService(TEST_SECRET, 30000L);
        UUID anonymousId = UUID.randomUUID();

        String token1 = service1.generateQrToken(anonymousId);
        String token2 = service2.generateQrToken(anonymousId);

        // Tokens con distinto TTL (expiration claim) deben ser distintos
        assertNotEquals(token1, token2,
                "Tokens con diferente TTL deben ser distintos para prevenir reutilización");
    }

}
