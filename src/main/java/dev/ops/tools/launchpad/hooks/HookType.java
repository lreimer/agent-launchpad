package dev.ops.tools.launchpad.hooks;

import java.util.Locale;
import java.util.Set;

/**
 * Normalized lifecycle hook types. Claude Code (PascalCase), GitHub Copilot (camelCase) and short URL aliases
 * such as {@code pre-tool} are all resolved to these types.
 */
public enum HookType {
    SESSION_START("sessionstart"),
    SESSION_END("sessionend"),
    USER_PROMPT_SUBMIT("userpromptsubmit", "userpromptsubmitted", "prompt"),
    MESSAGE_DISPLAY("messagedisplay", "message"),
    PRE_TOOL_USE("pretooluse", "pretool"),
    PERMISSION_REQUEST("permissionrequest", "permission"),
    POST_TOOL_USE("posttooluse", "posttool"),
    POST_TOOL_USE_FAILURE("posttoolusefailure", "posttoolfailure"),
    PRE_COMPACT("precompact"),
    POST_COMPACT("postcompact"),
    SUBAGENT_START("subagentstart"),
    SUBAGENT_STOP("subagentstop"),
    TASK_CREATED("taskcreated"),
    TASK_COMPLETED("taskcompleted"),
    STOP("stop", "agentstop"),
    STOP_FAILURE("stopfailure", "erroroccurred"),
    OTHER();

    private final Set<String> names;

    HookType(String... names) {
        this.names = Set.of(names);
    }

    public static HookType fromName(String name) {
        if (name == null || name.isBlank()) {
            return OTHER;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (HookType type : values()) {
            if (type.names.contains(normalized)) {
                return type;
            }
        }
        return OTHER;
    }

    /** Whether this hook blocks the agent until a decision has been made on the Launchpad. */
    public boolean isApproval() {
        return this == PRE_TOOL_USE || this == PERMISSION_REQUEST;
    }
}
