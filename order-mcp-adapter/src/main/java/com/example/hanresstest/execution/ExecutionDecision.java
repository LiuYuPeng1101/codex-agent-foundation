package com.example.hanresstest.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record ExecutionDecision(
        Status status,
        @JsonProperty("approval_id") UUID approvalId,
        @JsonProperty("execution_id") UUID executionId,
        @JsonProperty("expires_at") Instant expiresAt
) {
    public enum Status { PENDING, AUTHORIZED, REJECTED, EXPIRED }

    public ExecutionDecision {
        if (status == null || approvalId == null || expiresAt == null
                || (status == Status.AUTHORIZED) != (executionId != null)) {
            throw new IllegalArgumentException("无效执行授权响应");
        }
    }

    public UUID requireExecutionId() {
        if (status != Status.AUTHORIZED || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalStateException("执行尚未获准或授权已过期");
        }
        return executionId;
    }
}
