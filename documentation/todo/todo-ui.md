# naru-desktop todos
T1 (header chrome), T2 (rail tab glyphs + tooltips) and T3 (plan select + edit) are done — see
`../done/todo-ui-t1-header.md`, `../done/todo-ui-t2-rail-tabs.md`, `../done/todo-ui-t3-plans.md`.
please for each of the following, take one, define a plan (replace kthe content with more details), tehn implement, test and finally move this todo item to done folder

## T4 tasks
we need to restore a panel that shows current session's tasks and marks current taskk (hence current conversation) and allows swapping to other tasks

## T5 ui
when selecting one session all the window is redrawn? why ? i expected only models change, so why the menus are redrawn etc....

## T6 settings, Workspace environment
there is no trivial way to "add" a new config param (perhaps a new button right with other remove etc...)

## T7 settings, Custom Model Providers
Need a way to configurer custom model providers and models

## T8 conversation
i dont know why
addHistory(NaruMessage.engine(NMsg.ofC("[%s] invoke : %s", id(), op.toElement()).asFiner().withIntent(NMsgIntent.PROGRESS)));
ends up showing in the conversation by default, its 'finer' so it should not unless we raise the shown level (by the perhaps add a why to select that in the conversation when debug mode is on)

## T9 render markdown

`````
\\\\\\## Target: `NaruSession` (interface) \\\\\\\```java /**  * The execution context for a single conversation or batch run.  * <p>  * A session is the primary unit of work in Naru. It holds the project directory,  * the message history, the access policy, the active model configuration, and the  * set of tasks that have been started within it. A session is created through  * {@link NaruWorkspace#newSession()} and configured via {@link NaruSessionBuilder},  * then started with {@link #start()} or {@link #run(NaruTaskSpec)}.  * <p>  * A session has a lifecycle:

`````
is displayed verbatim, must support markdown in the conversation transcript, this must include the case of "streaming" aka all the text is not provided as one 
and should be implemented in the most efficient manner


## T10 input text disappears
sometimes when opening a session, the input text does not show up, the only way to make it appear is to select (or unselect) a tag. sresiwing the window does not help

