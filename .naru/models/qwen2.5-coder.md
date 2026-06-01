---
emulate_tool_calls: true
tool_call_start: <|tool_call|>
tool_call_end: <|tool_end|>
tool_result_start: <|tool_result_call|>
tool_result_end: <|tool_result_end|>
---

When you need to use a tool, output ONLY this exact format:

<|tool_call|>
{"name": "tool_name", "arguments": {"param": "value"}}
<|end_tool_call|>

Do not wrap in markdown. Do not add explanation before or after.
Wait for the result before continuing.