package dev.ops.tools.launchpad.state;

import dev.ops.tools.launchpad.hooks.AgentSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable state of one agent session. Guarded by the {@link AgentStateService} monitor.
 */
class SessionState {

    static final int STRIPS = 4;
    static final int HISTORY = 8;

    static final class Subagent {
        final String id;
        final String type;
        ActivityState state = ActivityState.RUNNING;

        Subagent(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }

    static final class HistoryEntry {
        final String key;
        final String toolName;
        ActivityState state;

        HistoryEntry(String key, String toolName, ActivityState state) {
            this.key = key;
            this.toolName = toolName;
            this.state = state;
        }
    }

    final String id;
    AgentSource source;
    TurnState turn = TurnState.IDLE;
    long turnChangedAt;
    long lastActivity;

    Long contextTokens;
    boolean compacting;

    long streamChars;
    boolean streaming;

    final List<Subagent> activeSubagents = new ArrayList<>();
    final Subagent[] strips = new Subagent[STRIPS];
    final Set<String> cancelledAgents = new HashSet<>();
    final List<HistoryEntry> history = new ArrayList<>();
    final Set<String> autoApprovedTools = new HashSet<>();

    SessionState(String id, AgentSource source) {
        this.id = id;
        this.source = source;
    }

    void turn(TurnState state, long now) {
        if (turn != state) {
            turn = state;
            turnChangedAt = now;
        }
    }

    void addHistory(HistoryEntry entry) {
        history.add(entry);
        if (history.size() > HISTORY) {
            history.removeFirst();
        }
    }

    HistoryEntry findHistory(String key, String toolName, ActivityState state) {
        if (key != null) {
            for (HistoryEntry entry : history) {
                if (key.equals(entry.key)) {
                    return entry;
                }
            }
            return null;
        }
        for (HistoryEntry entry : history.reversed()) {
            if (entry.state == state && toolName != null && toolName.equals(entry.toolName)) {
                return entry;
            }
        }
        return null;
    }

    void assignStrip(Subagent subagent) {
        for (int i = 0; i < STRIPS; i++) {
            if (strips[i] == null) {
                strips[i] = subagent;
                return;
            }
        }
        for (int i = 0; i < STRIPS; i++) {
            if (strips[i].state != ActivityState.RUNNING) {
                strips[i] = subagent;
                return;
            }
        }
    }
}
