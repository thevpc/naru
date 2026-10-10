----------------------------
delegate_to_model should not list all available model
should provide a new toll that does that listing with filter with capability
should also only list registered models
----------------------------
tag_remove - should not list tags
should add another tool to list with filter (--removable ect)

-----------------------------
sometimes the body is send empty, should detect and fix that

-----BODY
-----RESPONSE
{"error":"missing request body"}
-----RESPONSE
▌ ERROR calling model: Failed to communicate with ollama at http://localhost:11434/api/chat: Client error (HTTP HTTP400) from ollama: Bad Request


-----------------------------
add support for NText in tools definition:descriptionm then apply filter before sending to llm

