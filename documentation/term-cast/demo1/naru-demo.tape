Output naru-demo.gif
Set FontSize 14
Set Width 1200
Set Height 600
Set LoopOffset 0
Set Padding 25
Set Theme "Dracula"

Sleep 1s
Type "# NARU — Durable AI agents for the JVM"
Enter
Sleep 1s
Type "cd ~/xprojects/nuts-world/nuts-productivity/naru/"
Enter
Sleep 1s
Type "nuts naru"
Enter
Sleep 10s

Type "/model use qwen2.5-coder:14b"
Enter
Sleep 2s

Type "# define two concurrent tasks that coordinate via events"
Enter
Sleep 1s

Type "/use analyze"
Enter
Sleep 500ms
Type "10 /print ""analyzer: scanning project..."""
Enter
Sleep 500ms
Type "20 /sys find . -name ""*.java"" 2>/dev/null | head -20"
Enter
Sleep 500ms
Type "30 describe this project in one sentence. do not call any tool."
Enter
Sleep 500ms
Type "40 /fire analysisDone --to=siblings result=$last_response"
Enter
Sleep 500ms
Type "50 /print ""analyzer: done"""
Enter
Sleep 1s

Type "/use reporter"
Enter
Sleep 500ms
Type "10 /print ""reporter: waiting for analysis..."""
Enter
Sleep 500ms
Type "20 /wait --for=event(analysisDone)"
Enter
Sleep 500ms
Type "30 /print ""reporter: ${event.payload.result}"""
Enter
Sleep 1s

Type "/routine main"
Enter
Sleep 500ms
Type "/start analyze"
Enter
Sleep 500ms
Type "/start receiver"
Enter
Sleep 500ms
Type "/wait --for=children"
Enter
Sleep 500ms
Type "/print ""both tasks completed"""
Enter
Sleep 1s

Type "# run it"
Enter
Sleep 500ms
Type "/routine run main"
Enter
Sleep 60s

Type "/exit"
Enter
Sleep 3s