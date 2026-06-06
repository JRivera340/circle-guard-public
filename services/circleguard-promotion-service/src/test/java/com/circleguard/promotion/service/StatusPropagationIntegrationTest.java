package com.circleguard.promotion.service;

import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StatusPropagationIntegrationTest {

    @Mock
    private UserNodeRepository userNodeRepository;
    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private CircleNodeRepository circleNodeRepository;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private HealthStatusService healthStatusService;

    /**
     * El cliente Neo4j usa una API fluida (query().bind().to()...fetch().one()).
     * Aquí dejamos toda la cadena devolviendo mocks no nulos y un resultado vacío,
     * de modo que la lógica de propagación corre sin grafo real. Es lenient porque
     * no todos los métodos usan todos los eslabones (run() vs fetch()).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubNeo4jChain() {
        Neo4jClient.UnboundRunnableSpec unbound = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.OngoingBindSpec bindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        Neo4jClient.RunnableSpec runnableSpec = mock(Neo4jClient.RunnableSpec.class);
        Neo4jClient.RecordFetchSpec fetchSpec = mock(Neo4jClient.RecordFetchSpec.class);

        lenient().when(neo4jClient.query(anyString())).thenReturn(unbound);
        lenient().when(unbound.bind(any())).thenReturn(bindSpec);
        lenient().when(runnableSpec.bind(any())).thenReturn(bindSpec);
        lenient().when(bindSpec.to(anyString())).thenReturn(runnableSpec);
        lenient().when(runnableSpec.fetch()).thenReturn(fetchSpec);
        lenient().when(fetchSpec.one()).thenReturn(Optional.empty());
    }

    @Test
    void shouldUpdateRedisOnConfirmedStatus() {
        // Si alguien da positivo, hay que avisarle a Redis de una para que no lo dejen entrar
        String anonId = UUID.randomUUID().toString();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stubNeo4jChain();

        healthStatusService.updateStatus(anonId, "CONFIRMED");

        verify(valueOperations).multiSet(any());
    }

    @Test
    void shouldSendKafkaEventWhenStatusChanges() {
        // Esto es para que el servicio de notificaciones sepa que hay que mandar correos
        String anonId = UUID.randomUUID().toString();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stubNeo4jChain();

        healthStatusService.updateStatus(anonId, "CONFIRMED");

        verify(kafkaTemplate, atLeastOnce()).send(anyString(), eq(anonId), any());
    }

    @Test
    void resolveStatusShouldResetRedisKey() {
        // Cuando el administrador limpia el estado, el usuario vuelve a estar activo en Redis
        String anonId = UUID.randomUUID().toString();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stubNeo4jChain();

        healthStatusService.resolveStatus(anonId, true);

        verify(valueOperations).multiSet(any());
    }

    @Test
    void recoveredStatusShouldHaveExpiration() {
        // Los recuperados no son eternos, el estado en Redis debe expirar
        String anonId = UUID.randomUUID().toString();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stubNeo4jChain();

        healthStatusService.promoteToRecovered(anonId);

        verify(redisTemplate).expire(eq("user:status:" + anonId), any());
    }

    @Test
    void shouldCheckSystemSettingsBeforePropagation() {
        // Antes de propagar en el grafo, miramos la configuración del sistema
        String anonId = UUID.randomUUID().toString();
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stubNeo4jChain();

        healthStatusService.updateStatus(anonId, "CONFIRMED");

        verify(systemSettingsRepository).getSettings();
    }
}
