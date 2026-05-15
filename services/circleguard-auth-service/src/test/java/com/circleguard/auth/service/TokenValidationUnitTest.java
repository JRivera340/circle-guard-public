package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TokenValidationUnitTest {

    private QrTokenService qrTokenService;

    private final String secret = "my-qr-secret-key-for-dev-1234567890";
    private final long expiration = 300000; // 5 mins

    @BeforeEach
    void setUp() {
        qrTokenService = new QrTokenService(secret, expiration);
    }

    @Test
    void shouldHaveAnonymousIdInToken() {
        // Validamos que el token que generamos tenga el ID del usuario
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);
        
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
                
        assertEquals(anonymousId.toString(), claims.getSubject());
    }

    @Test
    void tokenShouldNotBeExpiredRightAway() {
        // El token no debería nacer vencido
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);
        
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
                
        assertTrue(claims.getExpiration().after(new Date()));
    }

    @Test
    void signatureShouldBeValid() {
        // Verificamos que la firma sea legible con nuestra llave secreta
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);
        
        assertDoesNotThrow(() -> {
            Key key = Keys.hmacShaKeyFor(secret.getBytes());
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
        });
    }

    @Test
    void shouldFailWithInvalidSecret() {
        // Si intentamos validar con otra llave, debería rebotar
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);
        String wrongSecret = "esta-llave-no-es-la-correcta-12345";
        
        assertThrows(Exception.class, () -> {
            Key key = Keys.hmacShaKeyFor(wrongSecret.getBytes());
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
        });
    }

    @Test
    void issuerShouldBeCircleGuardAuth() {
        // El emisor del token debe ser el servicio de Auth
        UUID anonymousId = UUID.randomUUID();
        String token = qrTokenService.generateQrToken(anonymousId);
        
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
                
        assertEquals("CircleGuard-Auth", claims.getIssuer());
    }
}
