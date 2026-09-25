package com.example.hanresstest;

import com.example.hanresstest.execution.*;
import com.example.hanresstest.gateway.OrderGateway;
import com.example.hanresstest.security.BusinessIdentity;
import com.example.hanresstest.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionBoundaryTests {
    private final BusinessIdentity identity = new BusinessIdentity("user", "tenant", Set.of("support.agent"));
    private final String conversationId = UUID.randomUUID().toString();

    @ParameterizedTest
    @EnumSource(value = ExecutionDecision.Status.class, names = {"PENDING", "REJECTED", "EXPIRED"})
    void noOmsWriteWithoutAuthorization(ExecutionDecision.Status status) {
        var gateway = mock(OrderGateway.class);
        var authorizer = mock(ExecutionAuthorizer.class);
        when(authorizer.prepare(anyString(), anyString(), anyMap(), any())).thenReturn(
                new ExecutionDecision(status, UUID.randomUUID(), null, Instant.now().plusSeconds(600)));
        var service = new OrderService(gateway, authorizer, JsonMapper.builder().build());
        assertEquals(status.name(), service.cancelOrder("1001", identity, conversationId).get("status").asText());
        verifyNoInteractions(gateway);
    }

    @Test
    void expiredIssuedIdCannotReachOms() {
        var gateway = mock(OrderGateway.class);
        var authorizer = mock(ExecutionAuthorizer.class);
        when(authorizer.prepare(anyString(), anyString(), anyMap(), any())).thenReturn(
                new ExecutionDecision(ExecutionDecision.Status.AUTHORIZED, UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(1)));
        var service = new OrderService(gateway, authorizer, JsonMapper.builder().build());
        assertThrows(IllegalStateException.class, () -> service.cancelOrder("1001", identity, conversationId));
        verifyNoInteractions(gateway);
    }

    @Test
    void authorizationFailureNeverFallsThroughToOms() {
        var gateway = mock(OrderGateway.class);
        var authorizer = mock(ExecutionAuthorizer.class);
        when(authorizer.prepare(anyString(), anyString(), anyMap(), any())).thenThrow(new IllegalStateException("unavailable"));
        var service = new OrderService(gateway, authorizer, JsonMapper.builder().build());
        assertThrows(IllegalStateException.class, () -> service.cancelOrder("1001", identity, conversationId));
        verifyNoInteractions(gateway);
    }
}
