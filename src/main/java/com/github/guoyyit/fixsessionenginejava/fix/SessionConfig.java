package com.github.guoyyit.fixsessionenginejava.fix;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable session configuration.
 */
public final class SessionConfig {

    public static final String DEFAULT_BEGIN_STRING = "FIX.4.4";
    public static final Duration DEFAULT_HEART_BT_INT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_RESEND_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_RESEND_ATTEMPTS = 3;
    public static final int DEFAULT_MAX_OUTBOUND_QUEUE = 10_000;
    public static final Duration DEFAULT_RESET_TIMEOUT = Duration.ofSeconds(10);

    private final String beginString;
    private final Duration heartBtInt;
    private final Duration resendTimeout;
    private final int maxResendAttempts;
    private final Path storagePath;
    private final int maxOutboundQueueSize;
    private final RejectPolicy rejectPolicy;
    private final Duration resetTimeout;
    private final Set<String> applicationMsgTypes;

    SessionConfig(String beginString,
                  Duration heartBtInt,
                  Duration resendTimeout,
                  int maxResendAttempts,
                  Path storagePath,
                  int maxOutboundQueueSize,
                  RejectPolicy rejectPolicy,
                  Duration resetTimeout,
                  Set<String> applicationMsgTypes) {
        this.beginString = beginString;
        this.heartBtInt = heartBtInt;
        this.resendTimeout = resendTimeout;
        this.maxResendAttempts = maxResendAttempts;
        this.storagePath = storagePath;
        this.maxOutboundQueueSize = maxOutboundQueueSize;
        this.rejectPolicy = rejectPolicy;
        this.resetTimeout = resetTimeout;
        this.applicationMsgTypes = Set.copyOf(applicationMsgTypes);
    }

    public String getBeginString() {
        return beginString;
    }

    public Duration getHeartBtInt() {
        return heartBtInt;
    }

    public Duration getResendTimeout() {
        return resendTimeout;
    }

    public int getMaxResendAttempts() {
        return maxResendAttempts;
    }

    public Path getStoragePath() {
        return storagePath;
    }

    public int getMaxOutboundQueueSize() {
        return maxOutboundQueueSize;
    }

    public RejectPolicy getRejectPolicy() {
        return rejectPolicy;
    }

    public Duration getResetTimeout() {
        return resetTimeout;
    }

    /** Application MsgTypes accepted by this session; others are unknown/rejected. */
    public Set<String> getApplicationMsgTypes() {
        return applicationMsgTypes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String beginString = DEFAULT_BEGIN_STRING;
        private Duration heartBtInt = DEFAULT_HEART_BT_INT;
        private Duration resendTimeout = DEFAULT_RESEND_TIMEOUT;
        private int maxResendAttempts = DEFAULT_MAX_RESEND_ATTEMPTS;
        private Path storagePath = Paths.get("target", "fix-sessions");
        private int maxOutboundQueueSize = DEFAULT_MAX_OUTBOUND_QUEUE;
        private RejectPolicy rejectPolicy = RejectPolicy.REJECT;
        private Duration resetTimeout = DEFAULT_RESET_TIMEOUT;
        private Set<String> applicationMsgTypes = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder beginString(String beginString) {
            this.beginString = Objects.requireNonNull(beginString);
            return this;
        }

        public Builder heartBtInt(Duration heartBtInt) {
            this.heartBtInt = requirePositive(heartBtInt, "heartBtInt");
            return this;
        }

        public Builder resendTimeout(Duration resendTimeout) {
            this.resendTimeout = requirePositive(resendTimeout, "resendTimeout");
            return this;
        }

        public Builder maxResendAttempts(int maxResendAttempts) {
            if (maxResendAttempts < 1) {
                throw new IllegalArgumentException("maxResendAttempts must be >= 1");
            }
            this.maxResendAttempts = maxResendAttempts;
            return this;
        }

        public Builder storagePath(Path storagePath) {
            this.storagePath = Objects.requireNonNull(storagePath);
            return this;
        }

        public Builder maxOutboundQueueSize(int maxOutboundQueueSize) {
            if (maxOutboundQueueSize < 1) {
                throw new IllegalArgumentException("maxOutboundQueueSize must be >= 1");
            }
            this.maxOutboundQueueSize = maxOutboundQueueSize;
            return this;
        }

        public Builder rejectPolicy(RejectPolicy rejectPolicy) {
            this.rejectPolicy = Objects.requireNonNull(rejectPolicy);
            return this;
        }

        public Builder resetTimeout(Duration resetTimeout) {
            this.resetTimeout = requirePositive(resetTimeout, "resetTimeout");
            return this;
        }

        /** Register one application MsgType that {@link Application#fromApp} receives. */
        public Builder applicationMsgType(String msgType) {
            this.applicationMsgTypes.add(Objects.requireNonNull(msgType));
            return this;
        }

        /** Register application MsgTypes that {@link Application#fromApp} receives. */
        public Builder applicationMsgTypes(String... msgTypes) {
            for (String msgType : msgTypes) {
                applicationMsgType(msgType);
            }
            return this;
        }

        private static Duration requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        public SessionConfig build() {
            return new SessionConfig(beginString, heartBtInt, resendTimeout, maxResendAttempts,
                    storagePath, maxOutboundQueueSize, rejectPolicy, resetTimeout,
                    applicationMsgTypes);
        }
    }
}
