---
emulate_tool_calls: true
tool_call_start: "<tool_call>"
tool_call_end: "</tool_call>"
tool_result_start: "<tool_result>"
tool_result_end: "</tool_result>"
---

When you need to use a tool, output ONLY the JSON block wrapped in <tool_call> tags:

<tool_call>
{"name": "tool_name", "arguments": {"param": "value"}}
</tool_call>

Do not wrap in markdown. Do not add explanation before or after.
Wait for the result before continuing.