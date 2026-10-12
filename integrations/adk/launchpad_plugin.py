"""Google ADK plugin that forwards agent lifecycle callbacks to the Agent Launchpad daemon.

Usage::

    from google.adk.runners import InMemoryRunner
    from launchpad_plugin import LaunchpadPlugin

    runner = InMemoryRunner(agent=root_agent, plugins=[LaunchpadPlugin()])

The plugin posts Claude Code style hook payloads (``hook_event_name``, ``session_id``, ``tool_name``, ...)
to ``POST /api/v1/hooks/adk``. Tool calls block until the approval is resolved on the Launchpad (or the
daemon times out) and are denied when the daemon answers with ``{"decision": "deny"}``.
"""

import asyncio
import json
import logging
import os
import urllib.error
import urllib.request
from typing import Any, Optional

from google.adk.agents.base_agent import BaseAgent
from google.adk.agents.callback_context import CallbackContext
from google.adk.agents.invocation_context import InvocationContext
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.adk.plugins.base_plugin import BasePlugin
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.tool_context import ToolContext
from google.genai import types

logger = logging.getLogger(__name__)

DEFAULT_URL = os.environ.get("AGENT_LAUNCHPAD_URL", "http://localhost:8080/api/v1/hooks/adk")


class LaunchpadPlugin(BasePlugin):
    """Visualises and controls ADK runs with a Novation Launchpad Mini MK2."""

    def __init__(self, url: str = DEFAULT_URL, approval_timeout: float = 300.0, timeout: float = 5.0):
        super().__init__(name="agent_launchpad")
        self._url = url
        self._approval_timeout = approval_timeout
        self._timeout = timeout
        self._root_agents: dict[str, str] = {}
        self._started_sessions: set[str] = set()

    # ------------------------------------------------------------------ transport

    def _post_sync(self, payload: dict, timeout: float) -> dict:
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self._url, data=data, method="POST", headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read()
                return json.loads(body) if body else {}
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            # the daemon is optional, never break the agent because of it
            logger.debug("Agent Launchpad not reachable: %s", e)
            return {}

    async def _post(self, event: str, session_id: str, timeout: Optional[float] = None, **fields: Any) -> dict:
        payload = {"hook_event_name": event, "session_id": session_id}
        payload.update({k: v for k, v in fields.items() if v is not None})
        return await asyncio.to_thread(self._post_sync, payload, timeout or self._timeout)

    # ------------------------------------------------------------------ helpers

    @staticmethod
    def _session_id(context: Any) -> str:
        invocation = getattr(context, "_invocation_context", context)
        session = getattr(invocation, "session", None)
        return getattr(session, "id", None) or "default"

    def _is_root(self, context: CallbackContext, agent_name: str) -> bool:
        return self._root_agents.get(context.invocation_id) in (None, agent_name)

    def _agent_id(self, context: CallbackContext, agent_name: str) -> Optional[str]:
        if self._is_root(context, agent_name):
            return None
        return f"{context.invocation_id}:{agent_name}"

    # ------------------------------------------------------------------ session & turn

    async def before_run_callback(self, *, invocation_context: InvocationContext) -> Optional[types.Content]:
        self._root_agents[invocation_context.invocation_id] = invocation_context.agent.name
        session_id = self._session_id(invocation_context)
        if session_id not in self._started_sessions:
            self._started_sessions.add(session_id)
            await self._post("SessionStart", session_id, source="startup")
        return None

    async def on_user_message_callback(
        self, *, invocation_context: InvocationContext, user_message: types.Content
    ) -> Optional[types.Content]:
        await self._post("UserPromptSubmit", self._session_id(invocation_context))
        return None

    async def after_run_callback(self, *, invocation_context: InvocationContext) -> None:
        self._root_agents.pop(invocation_context.invocation_id, None)
        await self._post("Stop", self._session_id(invocation_context))

    # ------------------------------------------------------------------ subagents

    async def before_agent_callback(
        self, *, agent: BaseAgent, callback_context: CallbackContext
    ) -> Optional[types.Content]:
        agent_id = self._agent_id(callback_context, agent.name)
        if agent_id:
            await self._post("SubagentStart", self._session_id(callback_context),
                             agent_id=agent_id, agent_type=agent.name)
        return None

    async def after_agent_callback(
        self, *, agent: BaseAgent, callback_context: CallbackContext
    ) -> Optional[types.Content]:
        agent_id = self._agent_id(callback_context, agent.name)
        if agent_id:
            await self._post("SubagentStop", self._session_id(callback_context),
                             agent_id=agent_id, agent_type=agent.name)
        return None

    # ------------------------------------------------------------------ model / streaming

    async def after_model_callback(
        self, *, callback_context: CallbackContext, llm_response: LlmResponse
    ) -> Optional[LlmResponse]:
        content = llm_response.content
        chars = sum(len(part.text or "") for part in (content.parts or [])) if content else 0
        usage = llm_response.usage_metadata
        await self._post("MessageDisplay", self._session_id(callback_context),
                         agent_id=self._agent_id(callback_context, callback_context.agent_name),
                         delta_chars=chars,
                         final=not llm_response.partial,
                         context_tokens=getattr(usage, "prompt_token_count", None) if usage else None)
        return None

    async def on_model_error_callback(
        self, *, callback_context: CallbackContext, llm_request: LlmRequest, error: Exception
    ) -> Optional[LlmResponse]:
        await self._post("StopFailure", self._session_id(callback_context),
                         error=type(error).__name__, error_details=str(error))
        return None

    # ------------------------------------------------------------------ tools & approvals

    async def before_tool_callback(
        self, *, tool: BaseTool, tool_args: dict[str, Any], tool_context: ToolContext
    ) -> Optional[dict]:
        response = await self._post("PreToolUse", self._session_id(tool_context),
                                    timeout=self._approval_timeout,
                                    agent_id=self._agent_id(tool_context, tool_context.agent_name),
                                    agent_type=tool_context.agent_name,
                                    tool_name=tool.name,
                                    tool_use_id=tool_context.function_call_id,
                                    tool_input=tool_args)
        if response.get("decision") == "deny" or response.get("stop"):
            reason = response.get("reason") or "Denied on the Launchpad"
            return {"error": reason}
        return None

    async def after_tool_callback(
        self, *, tool: BaseTool, tool_args: dict[str, Any], tool_context: ToolContext, result: dict
    ) -> Optional[dict]:
        await self._post("PostToolUse", self._session_id(tool_context),
                         agent_id=self._agent_id(tool_context, tool_context.agent_name),
                         tool_name=tool.name, tool_use_id=tool_context.function_call_id)
        return None

    async def on_tool_error_callback(
        self, *, tool: BaseTool, tool_args: dict[str, Any], tool_context: ToolContext, error: Exception
    ) -> Optional[dict]:
        await self._post("PostToolUseFailure", self._session_id(tool_context),
                         agent_id=self._agent_id(tool_context, tool_context.agent_name),
                         tool_name=tool.name, tool_use_id=tool_context.function_call_id,
                         error=str(error))
        return None
