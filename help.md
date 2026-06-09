```
なる› /?
▌ You can interact with Naru by issuing a command that can be on one the three forms :
▌ - Directives (start by '/' like '/help')
▌ - Routine lines (start by numbers like '10 could you check the file readme.md')
▌ - Prompt (any other string, like in 'could you check the file readme.md')
▌ Available directives are :
▌   ai directives:
▌     /context : show AI context
▌     /history : print or manipulate history
▌     /mode : manage AI modes
▌     /model : manage AI models
▌     /skills : manage AI skills
▌     /stat : show and manage stats
▌     /tools : manage AI tools
▌   fs directives:
▌     /cat : show file content
▌     /file : manipulate a single file
▌     /ls : list directory
▌     /pwd : print working directory
▌   general directives:
▌     /buffer : switch input mode (line <> buffer)
▌     /cd : change directory
▌     /exit : exit the agent
▌     /go : call model without additional prompt
▌     /print : print and append to context
▌     /sh : run shell command
▌     /system : run system command
▌   help directives:
▌     /help : show help
▌   routine directives:
▌     /else : else statement to close /if, /while and /for statements.
▌  it takes to arguments/options
▌     /elseif : elseif statement
▌     /end : end statement
▌     /for : start for bloc
▌     /if : start if statement
▌     /routine : create, update , list and run  routines
▌     /set : set variable value
▌     /while : start a while bloc
▌   session directives:
▌     /new : start a new session.
▌ current session will terminate but not saved.
▌     /reload : reload from last saved
▌     /reset : reset current session
▌     /restore : resume from last snapshot
▌     /save : save current session
▌     /session : manage sessions
▌   task directives:
▌     /call : call routine
▌     /fire : fire an event to self or another task
▌     /on : define and event inbox hook
▌     /sleep : define and event inbox hook
▌     /source : inline routine
▌     /start : start new task
▌     /task : manage tasks
▌     /wait : wait for an event or a task completion
▌ -------------
▌ /routine  [options...]
▌     create, update , list and run  routines
▌   /routine  clear
▌            clear current routine lines
▌   /routine  current
▌            shows current routine name
▌   /routine  delete <n1>-<n2>
▌            filter routine content lines to delete
▌   /routine
▌   /routine  list
▌            list routines
▌   /routine  main
▌            select 'main' current routine
▌   /routine  show [<n1>-<n2>]
▌            show current routine lines.
▌            when filter is provided, only selected files are shown.
▌            ex:
▌             /routine show -2..-1
▌             show last two lines
▌             /routine show 1-2
▌             show first two lines
▌             /routine show 1-2,4,-1
▌             show first two lines, 4th line and last line
▌   /routine  use <name>
▌            routine name (or path) to load
▌   /routine  [ help | --help ]
▌            show this help
▌ -------------
▌ /exit  [options...]
▌     exit the agent
▌   /exit   
▌            exit agent
▌   /exit  [ help | --help ]
▌            show this help
▌ -------------
▌ /print  [options...]
▌     print and append to context
▌   /print   <expression>
▌            print and append to context
▌            ex:
▌            /print "$message"
▌            evaluates $message and print the result
▌   /print  [ help | --help ]
▌            show this help
▌ -------------
▌ /tools  [options...]
▌     manage AI tools
▌   /tools  list
▌            list available tools
▌   /tools  run <tool-name>  [<key>=<value>...]
▌            run a tool by name with named arguments
▌            ex:
▌            /tools run file_read path=src/Main.java
▌   /tools  [ help | --help ]
▌            show this help
▌ -------------
▌ /stat  [options...]
▌     show and manage stats
▌   /stat   
▌            show and manage stats
▌   /stat  [ help | --help ]
▌            show this help
▌ -------------
▌ /model  [options...]
▌     manage AI models
▌   /model   <n>
▌            set model by index
▌   /model  alias
▌            list aliases
▌   /model  alias <alias>=<name>
▌            set alias
▌   /model  current
▌            show current model
▌   /model  install <model>
▌            model name to install
▌   /model
▌   /model  list
▌            list aliases
▌   /model  ps
▌            list loaded (in VRAM) models
▌   /model  unalias <alias>
▌            remove alias named <alias>
▌   /model  uninstall <model>
▌            model name to uninstall
▌   /model  unload <model>
▌            model name to unload
▌   /model  update <alias> <options>
▌            update option of the alias
▌   /model  update <alias> --alias=<value>
▌            update alias name
▌   /model  update <alias> --alias=<value>
▌            update alias name
▌   /model  update <alias> --model=<value>
▌            update model name
▌   /model  update <alias> --contextLength=<value>
▌            update context length (ex: 15b)
▌   /model  update <alias> --temperature=<value>
▌            update temperature length (ex: 0.6)
▌   /model  update <alias> --nucleusThreshold=<value>
▌            update nucleusThreshold (top_p) (ex: 0.6)
▌   /model  update <alias> --candidateCount=<value>
▌            update candidateCount ('top_k') (ex: 2)
▌   /model  update <alias> --maxTokens=<value>
▌            update maxTokens ('num_predict') (ex: 2)
▌   /model  update <alias> --stop=<value>
▌            update/append stop words ('stop') (ex: '<|start>')
▌   /model  use <model>
▌            model name or index (as given by 'list' subcommand) to select
▌   /model  use-global <model>
▌            model name to set as default globally
▌   /model  [ help | --help ]
▌            show this help
▌ -------------
▌ /mode  [options...]
▌     manage AI modes
▌   /mode   
▌            special
▌   /mode
▌   /mode  list
▌            list loaded skills
▌   /mode  set
▌            show loaded skills
▌   /mode  show
▌            show loaded skills
▌   /mode  [ help | --help ]
▌            show this help
▌ -------------
▌ /pwd  [options...]
▌     print working directory
▌   /pwd   
▌            print working directory
▌   /pwd  [ help | --help ]
▌            show this help
▌ -------------
▌ /cd  [options...]
▌     change directory
▌   /cd   <dir>
▌            change directory to <dir>
▌   /cd  [ help | --help ]
▌            show this help
▌ -------------
▌ /cat  [options...]
▌     show file content
▌   /cat   
▌            show file content
▌   /cat  [ help | --help ]
▌            show this help
▌ -------------
▌ /buffer  [options...]
▌     switch input mode (line <> buffer)
▌   /buffer   
▌            switch input mode (line <> buffer)
▌   /buffer  [ help | --help ]
▌            show this help
▌ -------------
▌ /history  [options...]
▌     print or manipulate history
▌   /history  all [<n1>-<n2>]
▌            list all context history (user only) selected lines, or all
▌   /history  clear
▌            delete all history lines
▌   /history  delete [<n1>-<n2>]
▌            delete all context history selected lines
▌   /history
▌   /history  list [<n1>-<n2>]
▌            list history (user only) selected lines, or all
▌   /history  trim <count>
▌            trim <count> lines
▌   /history  [ help | --help ]
▌            show this help
▌ -------------
▌ /session  [options...]
▌     manage sessions
▌   /session  copy
▌            copy current session to a new session
▌   /session  current
▌            show current session
▌   /session  delete <name>...
▌            delete session by name
▌   /session
▌   /session  list
▌            list saved sessions
▌   /session  load <name>...
▌            load session by name
▌   /session  new
▌            start a new session
▌   /session  private
▌            change current session visibility to private
▌   /session  public
▌            change current session visibility to public
▌   /session  purge
▌            purge all sessions
▌   /session  reload
▌            reload current session
▌   /session  reset
▌            reset current session
▌   /session  restore
▌            resume from last snapshot
▌   /session  save [<name>]
▌            save current session with optional name.
▌            when no name was provided, and this is a new session, a generated name will be guessed using the current model.
▌             when name is provided, it will be used to set name or rename the session.
▌   /session  [ help | --help ]
▌            show this help
▌ -------------
▌ /sh  [options...]
▌     run shell command
▌   /sh   
▌            run shell command
▌   /sh  [ help | --help ]
▌            show this help
▌ -------------
▌ /ls  [options...]
▌     list directory
▌   /ls   
▌            list directory
▌   /ls  [ help | --help ]
▌            show this help
▌ -------------
▌ /file  [options...]
▌     manipulate a single file
▌   /file  append <path> [--content=<content>] [--dry]
▌            append file content
▌   /file  edit <path> [--from=<from>] [--to=<to>] --content=<content>
▌            edit file content to replace a portion of lines with anew content to remove or update that part
▌   /file  find <path> [--pattern=<pattern>] [--regex|-e] [--case-sensitive|-!i] [--context-lines=<n>]
▌            search for files in a directory by content and show context-lines around matches
▌   /file  find <path> [--include=<file_name_pattern>] [--exclude=<file_name_pattern>]
▌            search for files in a directory by file name glob
▌   /file  find <path> [--max-matches=<n>] [--max-files=<n>] [--recursive|-r]
▌            search for files in a directory using limits and recursing behaviour
▌   /file  find <path> [--before=<d>] [--after=<d>]
▌            search for files in a directory using file modification date
▌   /file  grep <path> [--pattern=<pattern>] [--regex] [--context-lines=<n>] [--case-sensitive] [--max-matches=<n>]
▌            search content within a file to match pattern
▌   /file  read <path> [--from=<from>] [--to=<to>]
▌            read file content
▌   /file  write <path> [--content=<content>] [--dry]
▌            write file content
▌   /file  [ help | --help ]
▌            show this help
▌ -------------
▌ /set  [options...]
▌     set variable value
▌   /set   <var> = <expr>
▌            set variable value.
▌            ex:
▌            /set a=x*2
▌   /set   --task <var> = <expr>
▌            set task env variable value.
▌            ex:
▌            /set --task a=x*2
▌   /set   --session <var> = <expr>
▌            set session env variable value.
▌            ex:
▌            /set --session a=x*2
▌   /set  [ help | --help ]
▌            show this help
▌ -------------
▌ /skills  [options...]
▌     manage AI skills
▌   /skills  available
▌            list available skills
▌   /skills
▌   /skills  list
▌            list loaded skills
▌   /skills  load <name>
▌            load skill named <name>
▌   /skills  show <name> [<n1>-<n2>]
▌            show skill named <name> content wile listing only the selected files (or all if no filter)
▌   /skills  unload <name>
▌            unload skill named <name>
▌   /skills  [ help | --help ]
▌            show this help
▌ -------------
▌ /system  [options...]
▌     run system command
▌   /system   
▌            run system command
▌   /system  [ help | --help ]
▌            show this help
▌ -------------
▌ /while  [options...]
▌     start a while bloc
▌   /while   
▌            start a while bloc
▌   /while  [ help | --help ]
▌            show this help
▌ -------------
▌ /for  [options...]
▌     start for bloc
▌   /for   
▌            start for bloc
▌   /for  [ help | --help ]
▌            show this help
▌ -------------
▌ /if  [options...]
▌     start if statement
▌   /if   <condition>
▌            if statement with as any valid expression as condition.
▌            ex:
▌            /if n==1
▌            /print 'one'
▌            /elseif n==2
▌            /print 'two'
▌            /else
▌            /print 'else'
▌            /end
▌   /if  [ help | --help ]
▌            show this help
▌ -------------
▌ /else  [options...]
▌     else statement to close /if, /while and /for statements.
▌  it takes to arguments/options
▌   /else   
▌            else statement to close /if, /while and /for statements.
▌             it takes to arguments/options
▌   /else  [ help | --help ]
▌            show this help
▌ -------------
▌ /elseif  [options...]
▌     elseif statement
▌   /elseif   <condition>
▌            elseif branch with condition as any valid expression
▌   /elseif  [ help | --help ]
▌            show this help
▌ -------------
▌ /end  [options...]
▌     end statement
▌   /end   
▌            end statement
▌   /end  [ help | --help ]
▌            show this help
▌ -------------
▌ /reload  [options...]
▌     reload from last saved
▌   /reload   
▌            reload from last saved
▌   /reload  [ help | --help ]
▌            show this help
▌ -------------
▌ /new  [options...]
▌     start a new session.
▌ current session will terminate but not saved.
▌   /new   
▌            start a new session.
▌            current session will terminate but not saved.
▌   /new  [ help | --help ]
▌            show this help
▌ -------------
▌ /restore  [options...]
▌     resume from last snapshot
▌   /restore   
▌            resume from last snapshot
▌   /restore  [ help | --help ]
▌            show this help
▌ -------------
▌ /save  [options...]
▌     save current session
▌   /save   [<name>]
▌            save current session with optional name.
▌            when no name was provided, and this is a new session, a generated name will be guessed using the current model.
▌             when name is provided, it will be used to set name or rename the session.
▌   /save  [ help | --help ]
▌            show this help
▌ -------------
▌ /reset  [options...]
▌     reset current session
▌   /reset   
▌            reset current session
▌   /reset  [ help | --help ]
▌            show this help
▌ -------------
▌ /context  [options...]
▌     show AI context
▌   /context  agents [<n1>-<n2>]
▌            show AI context agents selected lines. this includes user-home, classpath, project and folder naru files.
▌            for each only selected lines are shown
▌   /context
▌   /context  all [<n1>-<n2>]
▌            show AI full context history (all sources).
▌            when providing lines filter, only selected lines are shown from each source. when no filter, all lines are displayed.
▌            ex:
▌             /context all -2..-1
▌             show last two lines of all sources
▌             /context all 1-2
▌             show first two lines of all sources
▌             /context all 1-2,4,-1
▌             show first two lines, 4th line and last line of all sources
▌   /context  classpath [<n1>-<n2>]
▌            show AI context classpath selected lines
▌   /context  files
▌            show AI agent files (not content lines)
▌   /context  folder [<n1>-<n2>]
▌            show AI context folder selected lines
▌   /context  project [<n1>-<n2>]
▌            show AI context project selected lines
▌   /context  skills [<n1>-<n2>]
▌            show AI context skills selected lines
▌   /context  system [<n1>-<n2>]
▌            show AI system context selected lines
▌   /context  user [<n1>-<n2>]
▌            show AI context user-home selected lines
▌   /context  [ help | --help ]
▌            show this help
▌ -------------
▌ /go  [options...]
▌     call model without additional prompt
▌   /go   
▌            call model without additional prompt
▌   /go  [ help | --help ]
▌            show this help
▌ -------------
▌ /call  [options...]
▌     call routine
▌   /call   <routine>...
▌            call one or more routines in the current task as a new frame
▌            <routine> can be routine name or routine path
▌   /call  [ help | --help ]
▌            show this help
▌ -------------
▌ /on  [options...]
▌     define and event inbox hook
▌   /on   <event> <routine> --from=<from-expression> <args>
▌            define an event inbox hook and the routine that shall be called when the event is received.
▌            event args are accessible as a special 'event.<key>' vars
▌            event expression
▌              any : any source task
▌              <number> : any task with id
▌              task(<number>) : any task with id
▌              parent : own parent
▌              child : any child
▌              sibling : any sibling (same parent)
▌              child(<number>) : any child of task with id <number>
▌   /on  [ help | --help ]
▌            show this help
▌ -------------
▌ /fire  [options...]
▌     fire an event to self or another task
▌   /fire   <event> [--to=<target-expression>...]  [--keep=<policy-expression>...] [<event-arg-key>=<value>...]
▌            send an event to self or one or more task an event inbox hook and the routine that shall be called when the event is received
▌            target-expression:
▌              all      : all tasks
▌              children : children tasks
▌              children : children tasks
▌              siblings : siblings tasks
▌              parent   : parent tasks
▌              <number> : task with id
▌              & operator : 'and' expression like in 'parent & children'
▌              | operator : 'or' expression like in 'parent | children'
▌            policy-expression:
▌              forever     : retain forever
▌              once        : retain until consumed once
▌              max(<nbr>)  : retain until consumed <nbr> times
▌              ttl(<nbr>)  : retain for max <nbr> seconds
▌              ttl(<duration>)  : retain for max <duration> as string. ex : '3s'
▌              default  : shortcut for once|tt('1h')
▌              & operator : 'and' expression like in 'once & tt('1h')'
▌              | operator : 'or' expression like in 'once | tt('1h')'
▌   /fire  [ help | --help ]
▌            show this help
▌ -------------
▌ /source  [options...]
▌     inline routine
▌   /source   <routine>...
▌            inline and run one or more routines in the current task
▌            <routine> can be routine name or routine path
▌   /source  [ help | --help ]
▌            show this help
▌ -------------
▌ /start  [options...]
▌     start new task
▌   /start   <routine>...
▌            start one or more routines as a single consecutive new task
▌            <routine> can be routine name or routine path
▌   /start  [ help | --help ]
▌            show this help
▌ -------------
▌ /task  [options...]
▌     manage tasks
▌   /task  current
▌            display current task
▌   /task  frames [<id>...]
▌            show frames (all debug infos, including vars, params...) of the given task ids
▌            when no id is provided, shows frames of the current task
▌   /task  hold <id>...
▌            hold (pause) tasks of with the provided task ids
▌   /task  kill <id>...
▌            kills tasks of with the provided task ids
▌   /task
▌   /task  list
▌            list current tasks
▌   /task  stacktrace [<id>...]
▌            show stacktrace of the given task ids
▌            when no id is provided, shows stacktrace of the current task
▌   /task  unhold <id>...
▌            unhold (pause) tasks of with the provided task ids
▌   /task  [ help | --help ]
▌            show this help
▌ -------------
▌ /sleep  [options...]
▌     define and event inbox hook
▌   /sleep   <event> <routine> <args>
▌            define an event inbox hook and the routine that shall be called when the event is received.
▌            event args are accessible as a special 'event.<key>' vars
▌   /sleep  [ help | --help ]
▌            show this help
▌ -------------
▌ /wait  [options...]
▌     wait for an event or a task completion
▌   /wait   --for=<event> --from=tid|children|parent|siblings
▌            wait for an event from the provided (if any) tasks.
▌            when no event, waits for termination
▌            from-expression:
▌              any
▌              parent
▌              sibling
▌              child
▌              child(<taskId>)
▌              taskId(<taskId>)
▌              <taskId>
▌   /wait  [ help | --help ]
▌            show this help
▌ -------------
▌ Type '/<directivename> help' to get help for a directive.
▌ Type '/exit' to quit the interpreter
```
