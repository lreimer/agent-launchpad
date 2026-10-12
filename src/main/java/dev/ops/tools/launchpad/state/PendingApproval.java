package dev.ops.tools.launchpad.state;

import dev.ops.tools.launchpad.hooks.HookEvent;

import java.util.concurrent.CompletableFuture;

/**
 * A blocking hook (PreToolUse / PermissionRequest) waiting for a pad press.
 */
public record PendingApproval(long id, HookEvent event, CompletableFuture<ApprovalDecision> decision) {
}
