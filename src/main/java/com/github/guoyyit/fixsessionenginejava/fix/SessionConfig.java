package com.github.guoyyit.fixsessionenginejava.fix;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tunable session parameters. A single configuration deterministically
 * controls timing, retry bounds, queue bounds and persistence layout.
 */
public final class SessionConfig {
    private String beginString = "FIX.4.4";
    private Duration heartbeatInterval = Duration.ofSeconds(30);
    private Duration testRequestTimeout = Duration.ofSeconds(10);
    private Duration resendTimeout = Duration.ofSeconds(5);
    private int maxResendAttempts = 3;
    private int sendQueueCapacity = 1024;
    private RejectPolicy rejectPolicy = RejectPolicy.REJECT_AND_DISCONNECT;
    private Path storagePath;
    private boolean resetOnLogon = false;
    private Set<String> allowedAppMsgTypes = new LinkedHashSet<>();

    public String getBeginString() {
        return beginString;
    }

    public void setBeginString(String beginString) {
        this.beginString = beginString;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getTestRequestTimeout() {
        return testRequestTimeout;
    }

    public void setTestRequestTimeout(Duration testRequestTimeout) {
        this.testRequestTimeout = testRequestTimeout;
    }

    public Duration getResendTimeout() {
        return resendTimeout;
    }

    public void setResendTimeout(Duration resendTimeout) {
        this.resendTimeout = resendTimeout;
    }

    public int getMaxResendAttempts() {
        return maxResendAttempts;
    }

    public void setMaxResendAttempts(int maxResendAttempts) {
        this.maxResendAttempts = maxResendAttempts;
    }

    public int getSendQueueCapacity() {
        return sendQueueCapacity;
    }

    public void setSendQueueCapacity(int sendQueueCapacity) {
        this.sendQueueCapacity = sendQueueCapacity;
    }

    public RejectPolicy getRejectPolicy() {
        return rejectPolicy;
    }

    public void setRejectPolicy(RejectPolicy rejectPolicy) {
        this.rejectPolicy = rejectPolicy;
    }

    public Path getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(Path storagePath) {
        this.storagePath = storagePath;
    }

    public boolean isResetOnLogon() {
        return resetOnLogon;
    }

    public void setResetOnLogon(boolean resetOnLogon) {
        this.resetOnLogon = resetOnLogon;
    }

    /**
     * Application msgTypes accepted for delivery to {@code FixApplication}.
     * An empty set means every non-administrative msgType is accepted.
     * Unknown administrative types are always rejected.
     */
    public Set<String> getAllowedAppMsgTypes() {
        return Collections.unmodifiableSet(allowedAppMsgTypes);
    }

    public void setAllowedAppMsgTypes(Set<String> allowedAppMsgTypes) {
        this.allowedAppMsgTypes =
                new LinkedHashSet<>(allowedAppMsgTypes == null
                        ? Collections.emptySet() : allowedAppMsgTypes);
    }

    public SessionConfig allowAppMsgType(String msgType) {
        this.allowedAppMsgTypes.add(msgType);
        return this;
    }
}
