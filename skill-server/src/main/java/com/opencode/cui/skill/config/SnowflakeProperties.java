package com.opencode.cui.skill.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skill.snowflake")
public class SnowflakeProperties {

    private long epochMs = 1735689600000L;

    private long serviceCode = 1L;

    private long workerId = 1L;

    private int serviceBits = 4;

    private int workerBits = 10;

    private int sequenceBits = 12;

    private ClockBackwardsStrategy clockBackwardsStrategy = ClockBackwardsStrategy.WAIT;

    private long maxBackwardMs = 5L;

    @PostConstruct
    public void validateLayout() {
        if (epochMs < 0 || serviceCode < 0 || workerId < 0 || maxBackwardMs < 0) {
            throw new IllegalStateException("Snowflake numeric properties must be non-negative");
        }
        if (serviceBits <= 0 || workerBits <= 0 || sequenceBits <= 0) {
            throw new IllegalStateException("Snowflake bit allocation must be positive");
        }
        int allocatedBits = serviceBits + workerBits + sequenceBits;
        if (allocatedBits >= Long.SIZE - 1) {
            throw new IllegalStateException("Snowflake bit allocation exceeds signed long capacity");
        }
        long maxServiceCode = maxValueForBits(serviceBits);
        if (serviceCode > maxServiceCode) {
            throw new IllegalStateException("skill.snowflake.service-code exceeds " + maxServiceCode);
        }
        long maxWorkerId = maxValueForBits(workerBits);
        if (workerId > maxWorkerId) {
            throw new IllegalStateException("skill.snowflake.worker-id exceeds " + maxWorkerId);
        }
    }

    public long maxSequence() {
        return maxValueForBits(sequenceBits);
    }

    public long timestampShift() {
        return serviceBits + workerBits + sequenceBits;
    }

    public long serviceCodeShift() {
        return workerBits + sequenceBits;
    }

    public long workerShift() {
        return sequenceBits;
    }

    private long maxValueForBits(int bits) {
        return (1L << bits) - 1;
    }

    public enum ClockBackwardsStrategy {
        WAIT,
        REJECT
    }
}
