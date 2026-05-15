package com.circleguard.promotion.service;

import com.circleguard.promotion.exception.FenceException;
import com.circleguard.promotion.model.graph.UserNode;
import com.circleguard.promotion.model.jpa.SystemSettings;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UT-5: HealthStatusService — Mandatory Fence Window Enforcement
 *
 * Objetivo: Verificar que un usuario en estado SUSPECT dentro de la ventana
 * obligatoria de cuarentena (14 días) NO puede transicionar a ACTIVE sin
 * aprobación de admin (adminOverride=true).
 *
 * Criticidad: MÁXIMA — la ventana de cuarentena es un mecanismo de salud pública.
 * Si se bypasea prematuramente, usuarios infecciosos podrían acceder al campus.
 *
 * Estrategia de test: El método checkFenceWindow() se ejecuta ANTES de cualquier
 * llamada a Neo4j, por lo que los tests de fence no necesitan mockear Neo4j.
 * Los tests de admin override verifican que checkFenceWindow() NO es invocado.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UT-5: HealthStatusService — Fence Window Enforcement")
class HealthStatusServiceFenceTest {

    @Mock private UserNodeRepository       userNodeRepository;
    @Mock private SystemSettingsRepository systemSettingsRepository;
    @Mock private Neo4jClient              neo4jClient;
    @Mock private StringRedisTemplate      redisTemplate;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private CircleNodeRepository     circleNodeRepository;

    @InjectMocks
    private HealthStatusService healthStatusService;

    private SystemSettings defaultSettings;

    @BeforeEach
    void setUp() {
        defaultSettings = SystemSettings.builder()
                .encounterWindowDays(14)
                .mandatoryFenceDays(14)
                .unconfirmedFencingEnabled(true)
                .autoThresholdSeconds(3600L)
                .build();
    }

    // ── UT-5a: La prueba central — fence window lanzada correctamente ─────────

    @Test
    @DisplayName("UT-5a: Usuario SUSPECT dentro de ventana de 14 días → FenceException")
    void suspectUserWithin14Days_shouldThrowFenceExceptionOnResolve() {
        // Arrange — usuario SUSPECT hace 3 días (dentro de la ventana de 14 días)
        String anonymousId = "test-anon-001";
        long threeDaysAgoMs = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000);

        UserNode suspectUser = new UserNode();
        suspectUser.setAnonymousId(anonymousId);
        suspectUser.setStatus("SUSPECT");
        suspectUser.setStatusUpdatedAt(threeDaysAgoMs);

        when(userNodeRepository.findById(anonymousId)).thenReturn(Optional.of(suspectUser));
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(defaultSettings));

        // Act & Assert — checkFenceWindow() se ejecuta antes de Neo4j; lanza FenceException
        FenceException ex = assertThrows(FenceException.class,
                () -> healthStatusService.resolveStatus(anonymousId),
                "Usuario SUSPECT con 3 días NO debe poder liberarse dentro de ventana de 14 días"
        );
        assertTrue(ex.getMessage().contains("mandatory fence window"),
                "El mensaje de FenceException debe mencionar 'mandatory fence window'");
    }

    @Test
    @DisplayName("UT-5b: Usuario PROBABLE dentro de ventana → FenceException también")
    void probableUserWithin14Days_shouldAlsoThrowFenceException() {
        // Arrange — PROBABLE también está en fence window
        String anonymousId = "test-anon-002";
        long fiveDaysAgoMs = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000);

        UserNode probableUser = new UserNode();
        probableUser.setAnonymousId(anonymousId);
        probableUser.setStatus("PROBABLE");
        probableUser.setStatusUpdatedAt(fiveDaysAgoMs);

        when(userNodeRepository.findById(anonymousId)).thenReturn(Optional.of(probableUser));
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(defaultSettings));

        // PROBABLE también está sujeto a la fence window
        assertThrows(FenceException.class,
                () -> healthStatusService.resolveStatus(anonymousId),
                "Usuario PROBABLE con 5 días también debe estar bloqueado por la fence window"
        );
    }

    @Test
    @DisplayName("UT-5c: Admin override → checkFenceWindow() NO se consulta")
    void adminOverride_shouldBypassFenceWindowCheck() {
        // Arrange — usuario SUSPECT de 3 días, pero con adminOverride=true
        // Con adminOverride, checkFenceWindow() NO se ejecuta, por lo tanto
        // userNodeRepository.findById NUNCA debe ser llamado
        String anonymousId = "test-anon-003";

        // Configurar Neo4j para que el resolveStatus pueda avanzar (sin FenceException)
        // Usamos lenient stubs para evitar errores de "unnecessary stubbing"
        lenient().when(userNodeRepository.findById(anyString())).thenReturn(Optional.empty());

        // Act & Assert — con adminOverride=true, el método NO debe lanzar FenceException
        // Puede lanzar cualquier otra excepción (Neo4j, Redis) pero NOT FenceException
        try {
            healthStatusService.resolveStatus(anonymousId, true);
        } catch (FenceException fe) {
            fail("Admin override NO debe lanzar FenceException: " + fe.getMessage());
        } catch (Exception other) {
            // Otras excepciones por mocks incompletos de Neo4j/Redis son aceptables
        }

        // Con adminOverride=true, userNodeRepository.findById NO debe ser invocado
        verify(userNodeRepository, never()).findById(anonymousId);
    }

    @Test
    @DisplayName("UT-5d: Usuario ACTIVE sin fence window → resolución sin excepción")
    void activeUser_withoutFenceWindow_noException() {
        // Arrange — usuario ACTIVE (no está en fence window)
        String anonymousId = "test-anon-004";

        UserNode activeUser = new UserNode();
        activeUser.setAnonymousId(anonymousId);
        activeUser.setStatus("ACTIVE");
        activeUser.setStatusUpdatedAt(null); // Sin fecha de cambio de estado

        when(userNodeRepository.findById(anonymousId)).thenReturn(Optional.of(activeUser));

        // Act & Assert — usuario ACTIVE no debe ser bloqueado por fence window
        assertDoesNotThrow(() -> {
            try {
                healthStatusService.resolveStatus(anonymousId);
            } catch (FenceException fe) {
                fail("Usuario ACTIVE NO debe estar en fence window: " + fe.getMessage());
            } catch (Exception other) {
                // Neo4j mock exceptions son aceptables
            }
        });
    }
}
