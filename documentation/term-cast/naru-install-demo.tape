Output naru-install-demo.gif
Set FontSize 14
Set Width 1200
Set Height 600
Set LoopOffset 0
Set Padding 25
Set Theme "Dracula"

Type "# prepare environment, install naru, and test some REPL..."
Enter
Enter
Type "# ──────────────────────────────────────────
Enter
Type "# PHASE 1: running ollama, installing nuts
Enter
Type "# ──────────────────────────────────────────
Enter
Sleep 2s

Type "# We start launching ollama..."
Enter
Type "# skip if already installed and running..."
Enter
Type "# after downloading (https://github.com/ollama/ollama/releases) and installing"
Enter
Type "ollama serve >/dev/null 2>&1 &"
Enter
Sleep 3s

Type "# Let's pull a model..."
Enter
Sleep 1s
Type "ollama pull qwen2.5-coder:14b"
Enter
Type "# model already cached, this completes instantly.
Enter
Sleep 3s

Type "# Then we install nuts package manager..."
Enter
Type "# skip if already installed..."
Enter
Sleep 2s

Type "curl -s https://thevpc.net/nuts/install-latest.sh | bash"
Enter
Sleep 3s

Type "# source .bashrc to make nuts available in the current shell..."
Enter
Sleep 2s
Type ". ~/.bashrc"
Enter
Sleep 3s

Enter
Type "# ──────────────────────────
Enter
Type "# PHASE 2: installing naru
Enter
Type "# ──────────────────────────
Enter
Enter
Sleep 2s

Type "# installing naru is as easy as ..."
Enter
Sleep 2s

Type "nuts -y install naru"
Enter
Sleep 5s

Enter
Type "# ────────────────────────────────────"
Enter
Type "# PHASE 3: Interactive REPL Workflow"
Enter
Type "# ────────────────────────────────────"
Enter
Enter
Sleep 2s

Type "# Naru is installed. we select project folder and just run it..."
Enter
Type "# Am working here at naru's source code folder, but you can run it anywhere"
Enter
Sleep 2s
Type "cd ~/xprojects/nuts-world/nuts-productivity/naru/"
Enter
Sleep 2s
Type "nuts -y naru"
Enter
Sleep 15s

Type "# We are in naru REPL, lets check installed models"
Enter
Sleep 2s
Type "/model"
Enter
Sleep 3s

Type "# we select model by name or by index"
Enter
Type "# let's select qwen2.5-coder:14b (index 6)"
Enter
Type "/model set 6"
Enter
Sleep 2s

Type "# we check the model we selected"
Enter
Type "/model"
Enter
Sleep 3s

Type "# we list all available skills"
Enter
Type "/skill available"
Enter
Sleep 3s

Type "# we load a skill, javadoc.md file we pre-filled with some instructions"
Enter
Type "/skill load javadoc"
Enter
Sleep 3s

Type "# lets show what we have loaded so far"
Enter
Type "/context files"
Enter
Sleep 3s

Type "/context all 1-3"
Enter
Sleep 3s

Type "# now lets call the model with a targeted task"
Enter
Type "# note that, because we just started ollama, it will take a while to load the model..."
Enter
Sleep 1s
Type "as a senior architect, provide javadoc for /home/vpc/xprojects/nuts-world/nuts-productivity/naru/core/naru-api/src/main/java/net/thevpc/naru/api/agent/NaruAgent.java"
Enter
Sleep 45s

Type "# because we are in planning mode, changes to apply only shown not applied"
Sleep 2s

Enter
Type "# ────────────────────────────────────"
Enter
Type "# PHASE 4: Routines & Workflow"
Enter
Type "# ────────────────────────────────────"
Enter
Type "# we start to check what we have as routines"
Enter
Sleep 1s
Type "/routine list"
Enter
Sleep 1s
Type "# ensure the current routine name and content (should be empty)"
Enter
Type "/routine name"
Enter
Sleep 1s
Type "/routine"
Enter
Sleep 1s
Type "# we add some lines to the routine"
Enter
Type "20 Based on these files, describe what this project does in one sentence. do not call any tool."
Enter
Type "10 /sys find . -name *.java 2>/dev/null | head -20"
Enter
Sleep 1s
Type "# lets check the routine"
Enter
Type "/routine"
Sleep 1s
Enter
Type "# finally lets run the routine"
Enter
Type "/routine run"
Enter
Sleep 60s
Type "# Note that you can create multiple routines..."
Enter
Type "# and add conditions and loops (using /if, /while, /for...)"
Enter
Type "# and even create sub-routines (functions), check the documentation for more details..."
Enter

Type "# lets exit"
Enter
Sleep 2s
Type "/exit"
Enter
Sleep 3s

Type "# -----------------------------------------------"
Enter
Type "# ...なる is fully realized. Ready to transform your workflow."
Enter
Type "# -----------------------------------------------"
Enter
Enter
Sleep 10s
