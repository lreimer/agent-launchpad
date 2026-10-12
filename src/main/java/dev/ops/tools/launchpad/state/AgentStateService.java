package dev.ops.tools.launchpad.state;

import dev.ops.tools.launchpad.LaunchpadConfig;
import dev.ops.tools.launchpad.hooks.HookEvent;
import dev.ops.tools.launchpad.hooks.HookOutcome;
import dev.ops.tools.launchpad.hooks.HookType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Central state of all agent sessions and the long-polling approval bridge.
 * <p>
 * Hooks are applied via {@link #handle(HookEvent)}. Blocking hooks that require an approval are parked as
 * {@link PendingApproval} until a pad on the Launchpad resolves them or the configured timeout expires.
 */
@ApplicationScoped
public class AgentStateService {

    private static final Logger LOGGER = Logger.getLogger(AgentStateService.class);

    static final String REASON_APPROVED = "Approved on Launchpad";
    static final String REASON_APPROVED_SESSION = "Approved for this session on Launchpad";
    static final String REASON_DENIED = "Blocked on Launchpad";
    static final String REASON_CANCELLED = "Subagent cancelled on Launchpad";
    static final String REASON_EMERGENCY = "Emergency stop engaged on Launchpad";

    private static final int CHARS_PER_TOKEN = 4;

    @Inject
    LaunchpadConfig config;

    @Inject
    ContextEstimator contextEstimator;

    Clock clock = Clock.systemUTC();

    private final Map<String, SessionState> sessions = new LinkedHashMap<>();
    private final Deque<PendingApproval> approvals = new ArrayDeque<>();
    private SessionState focused;
    private boolean emergencyStop;
    private boolean compactionRequested;
    private long sequence;
    private Pattern approvalPattern;

    /**
     * Applies a hook event. The returned {@link Uni} completes immediately for non-blocking hooks, and when a
     * pad was pressed (or the approval timed out) for hooks that require an approval.
     */
    public Uni<HookOutcome> handle(HookEvent event) {
        Long tokens = event.contextTokens();
        if (tokens == null && event.transcriptPath() != null
                && event.type() != HookType.PRE_COMPACT && event.type() != HookType.POST_COMPACT) {
            var estimate = contextEstimator.estimate(event.transcriptPath());
            tokens = estimate.isPresent() ? estimate.getAsLong() : null;
        }

        PendingApproval pending;
        synchronized (this) {
            SessionState session = session(event);
            HookOutcome outcome = apply(session, event);
            if (tokens != null && !session.compacting) {
                session.contextTokens = tokens;
            }
            if (outcome != null) {
                return Uni.createFrom().item(outcome);
            }
            pending = new PendingApproval(++sequence, event, new CompletableFuture<>());
            approvals.addLast(pending);
        }

        LOGGER.infof("Waiting for approval of %s (%s) in session %s", event.toolName(), event.type(), event.sessionId());
        return Uni.createFrom().completionStage(pending.decision())
                .ifNoItem().after(config.approval().timeout()).recoverWithItem(() -> expire(pending))
                .onCancellation().invoke(() -> expire(pending))
                .map(decision -> decide(pending.event(), decision));
    }

    /** Resolves the oldest pending approval. Returns {@code false} if there is none. */
    public boolean resolve(ApprovalDecision decision) {
        PendingApproval pending;
        synchronized (this) {
            pending = approvals.pollFirst();
        }
        if (pending == null) {
            return false;
        }
        pending.decision().complete(decision);
        return true;
    }

    /** Cancels the subagent shown in the given strip (0..3) of the focused session. */
    public boolean cancelSubagent(int strip) {
        List<PendingApproval> affected = new ArrayList<>();
        synchronized (this) {
            if (focused == null || strip < 0 || strip >= SessionState.STRIPS) {
                return false;
            }
            SessionState.Subagent subagent = focused.strips[strip];
            if (subagent == null || subagent.state != ActivityState.RUNNING) {
                return false;
            }
            subagent.state = ActivityState.CANCELLED;
            focused.cancelledAgents.add(subagent.id);
            approvals.removeIf(p -> {
                boolean match = subagent.id.equals(p.event().agentId()) && focused.id.equals(p.event().sessionId());
                if (match) {
                    affected.add(p);
                }
                return match;
            });
        }
        affected.forEach(p -> p.decision().complete(ApprovalDecision.CANCEL_SUBAGENT));
        return true;
    }

    /** Toggles the emergency stop, engaging it blocks all pending and future approvals. */
    public boolean toggleEmergencyStop() {
        List<PendingApproval> affected;
        boolean engaged;
        synchronized (this) {
            emergencyStop = !emergencyStop;
            engaged = emergencyStop;
            affected = engaged ? new ArrayList<>(approvals) : List.of();
            if (engaged) {
                approvals.clear();
            }
        }
        affected.forEach(p -> p.decision().complete(ApprovalDecision.EMERGENCY_STOP));
        LOGGER.infof("Emergency stop %s", engaged ? "engaged" : "released");
        return engaged;
    }

    /** Toggles the manual compaction request indicator. */
    public synchronized boolean toggleCompactionRequest() {
        compactionRequested = !compactionRequested;
        return compactionRequested;
    }

    public synchronized List<PendingApproval> pendingApprovals() {
        return List.copyOf(approvals);
    }

    /** Builds an immutable snapshot of the focused session for rendering. */
    public synchronized LaunchpadView view() {
        long now = clock.millis();
        SessionState s = focused;
        if (s == null) {
            return new LaunchpadView(approvals.size(), TurnState.IDLE, 0, null, false,
                    Arrays.asList(new LaunchpadView.Strip[SessionState.STRIPS]), List.of(), 0, false,
                    emergencyStop, compactionRequested, false);
        }

        List<LaunchpadView.Strip> strips = new ArrayList<>();
        for (SessionState.Subagent subagent : s.strips) {
            if (subagent == null) {
                strips.add(null);
                continue;
            }
            ActivityState state = subagent.state;
            if (state == ActivityState.RUNNING && approvals.stream().anyMatch(p ->
                    subagent.id.equals(p.event().agentId()) && s.id.equals(p.event().sessionId()))) {
                state = ActivityState.WAITING;
            }
            strips.add(new LaunchpadView.Strip(subagent.id, subagent.type, state));
        }

        Double fraction = s.contextTokens == null ? null
                : Math.min(1.0, (double) s.contextTokens / Math.max(1, config.context().windowTokens()));
        long tokens = (s.streamChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        int perPad = Math.max(1, config.stream().tokensPerPad());
        int streamPads = (int) Math.min(8, (tokens + perPad - 1) / perPad);

        return new LaunchpadView(approvals.size(), s.turn, now - s.turnChangedAt, fraction, s.compacting,
                strips, s.history.stream().map(h -> h.state).toList(), streamPads, s.streaming,
                emergencyStop, compactionRequested, true);
    }

    private SessionState session(HookEvent event) {
        SessionState session = sessions.computeIfAbsent(event.sessionId(), id -> new SessionState(id, event.source()));
        session.source = event.source();
        session.lastActivity = clock.millis();
        return session;
    }

    /**
     * Applies the event to the session state. Returns the outcome, or {@code null} if an approval is required.
     */
    private HookOutcome apply(SessionState s, HookEvent event) {
        long now = clock.millis();
        if (event.type() != HookType.SESSION_END) {
            focused = s;
        }
        if (emergencyStop) {
            if (event.type().isApproval()) {
                recordDenied(s, event);
            }
            return HookOutcome.emergencyStop(REASON_EMERGENCY);
        }

        switch (event.type()) {
            case SESSION_START -> {
                s.turn(TurnState.IDLE, now);
                s.streaming = false;
                s.compacting = false;
                if (!"resume".equals(event.detail())) {
                    s.contextTokens = null;
                }
            }
            case SESSION_END -> endSession(s);
            case USER_PROMPT_SUBMIT -> {
                s.turn(TurnState.THINKING, now);
                s.streamChars = 0;
                s.streaming = false;
            }
            case MESSAGE_DISPLAY -> {
                s.turn(TurnState.THINKING, now);
                if (event.streamIndex() == 0) {
                    s.streamChars = 0;
                }
                s.streamChars += event.streamChars();
                s.streaming = !event.streamFinal();
            }
            case PRE_TOOL_USE, PERMISSION_REQUEST -> {
                s.turn(TurnState.THINKING, now);
                return approve(s, event);
            }
            case POST_TOOL_USE -> {
                s.turn(TurnState.THINKING, now);
                recordToolEnd(s, event, ActivityState.SUCCESS);
            }
            case POST_TOOL_USE_FAILURE -> {
                s.turn(TurnState.THINKING, now);
                recordToolEnd(s, event, ActivityState.FAILURE);
            }
            case PRE_COMPACT -> {
                s.compacting = true;
                compactionRequested = false;
            }
            case POST_COMPACT -> {
                s.compacting = false;
                s.contextTokens = null;
            }
            case SUBAGENT_START -> {
                s.turn(TurnState.THINKING, now);
                startSubagent(s, event);
            }
            case SUBAGENT_STOP -> stopSubagent(s, event);
            case TASK_CREATED -> s.addHistory(new SessionState.HistoryEntry(taskKey(event), null, ActivityState.RUNNING));
            case TASK_COMPLETED -> {
                SessionState.HistoryEntry entry = event.taskId() == null ? null : s.findHistory(taskKey(event), null, null);
                if (entry != null) {
                    entry.state = ActivityState.SUCCESS;
                } else {
                    s.addHistory(new SessionState.HistoryEntry(taskKey(event), null, ActivityState.SUCCESS));
                }
            }
            case STOP -> {
                s.turn(TurnState.STOPPED, now);
                s.streaming = false;
                s.compacting = false;
            }
            case STOP_FAILURE -> {
                s.turn(TurnState.FAILED, now);
                s.streaming = false;
            }
            case OTHER -> {
                // not visualized
            }
        }
        return HookOutcome.none();
    }

    private HookOutcome approve(SessionState s, HookEvent event) {
        if (event.agentId() != null && s.cancelledAgents.contains(event.agentId())) {
            recordDenied(s, event);
            return HookOutcome.deny(REASON_CANCELLED);
        }
        if (event.toolName() == null || !approvalPattern().matcher(event.toolName()).matches()) {
            recordToolStart(s, event);
            return HookOutcome.none();
        }
        if (s.autoApprovedTools.contains(event.toolName())) {
            recordToolStart(s, event);
            return HookOutcome.allow(REASON_APPROVED_SESSION);
        }
        return null;
    }

    private synchronized ApprovalDecision expire(PendingApproval pending) {
        approvals.remove(pending);
        return ApprovalDecision.TIMEOUT;
    }

    private synchronized HookOutcome decide(HookEvent event, ApprovalDecision decision) {
        SessionState s = sessions.get(event.sessionId());
        if (s == null) {
            s = new SessionState(event.sessionId(), event.source());
        }
        LOGGER.infof("Approval of %s in session %s resolved: %s", event.toolName(), event.sessionId(), decision);
        return switch (decision) {
            case ALLOW -> {
                recordToolStart(s, event);
                yield HookOutcome.allow(REASON_APPROVED);
            }
            case ALLOW_SESSION -> {
                if (event.toolName() != null) {
                    s.autoApprovedTools.add(event.toolName());
                }
                recordToolStart(s, event);
                yield HookOutcome.allowForSession(REASON_APPROVED_SESSION);
            }
            case DENY -> {
                recordDenied(s, event);
                yield HookOutcome.deny(REASON_DENIED);
            }
            case CANCEL_SUBAGENT -> {
                recordDenied(s, event);
                yield HookOutcome.deny(REASON_CANCELLED);
            }
            case EMERGENCY_STOP -> {
                recordDenied(s, event);
                yield HookOutcome.emergencyStop(REASON_EMERGENCY);
            }
            case TIMEOUT -> {
                recordToolStart(s, event);
                yield HookOutcome.none();
            }
        };
    }

    private void endSession(SessionState s) {
        sessions.remove(s.id);
        if (focused == s) {
            focused = sessions.values().stream()
                    .max((a, b) -> Long.compare(a.lastActivity, b.lastActivity))
                    .orElse(null);
        }
    }

    private void recordToolStart(SessionState s, HookEvent event) {
        if (event.type() == HookType.PERMISSION_REQUEST
                && s.findHistory(null, event.toolName(), ActivityState.RUNNING) != null) {
            return;
        }
        s.addHistory(new SessionState.HistoryEntry(event.toolUseId(), event.toolName(), ActivityState.RUNNING));
    }

    private void recordDenied(SessionState s, HookEvent event) {
        SessionState.HistoryEntry entry = s.findHistory(event.toolUseId(), event.toolName(), ActivityState.RUNNING);
        if (entry != null) {
            entry.state = ActivityState.DENIED;
        } else {
            s.addHistory(new SessionState.HistoryEntry(event.toolUseId(), event.toolName(), ActivityState.DENIED));
        }
    }

    private void recordToolEnd(SessionState s, HookEvent event, ActivityState state) {
        SessionState.HistoryEntry entry = s.findHistory(event.toolUseId(), event.toolName(), ActivityState.RUNNING);
        if (entry == null && event.toolUseId() != null) {
            entry = s.findHistory(null, event.toolName(), ActivityState.RUNNING);
        }
        if (entry != null) {
            entry.state = state;
        } else {
            s.addHistory(new SessionState.HistoryEntry(event.toolUseId(), event.toolName(), state));
        }
    }

    private void startSubagent(SessionState s, HookEvent event) {
        String id = event.agentId() != null ? event.agentId()
                : (event.agentType() != null ? event.agentType() : "subagent") + "#" + (++sequence);
        SessionState.Subagent subagent = new SessionState.Subagent(id, event.agentType());
        s.activeSubagents.add(subagent);
        s.assignStrip(subagent);
    }

    private void stopSubagent(SessionState s, HookEvent event) {
        SessionState.Subagent subagent = null;
        for (SessionState.Subagent candidate : s.activeSubagents) {
            if (event.agentId() != null && event.agentId().equals(candidate.id)) {
                subagent = candidate;
                break;
            }
        }
        if (subagent == null) {
            for (SessionState.Subagent candidate : s.activeSubagents) {
                if (event.agentType() != null && event.agentType().equals(candidate.type)) {
                    subagent = candidate;
                    break;
                }
            }
        }
        if (subagent == null) {
            return;
        }
        s.activeSubagents.remove(subagent);
        if (subagent.state != ActivityState.CANCELLED) {
            subagent.state = ActivityState.SUCCESS;
        }
    }

    private static String taskKey(HookEvent event) {
        return "task:" + event.taskId();
    }

    private Pattern approvalPattern() {
        if (approvalPattern == null) {
            approvalPattern = Pattern.compile(config.approval().toolPattern());
        }
        return approvalPattern;
    }
}
