#### Purpose

This small lib provides async/non-blocking actor-like entities.
 
Due to its simplicity and **tight integration with Java 8 CompletionStage/CompletableFuture API**, JCExt might be  better choice 
than Akka for cases where you do not need the full Erlang system imitation. 

Besides one may argue that the untyped nature of Erlang actors is 
not the best fit for the strictly typed languages like Java.

Simplest way to use JCExt in your project is thru https://jitpack.io

For gradle build: 
```groovy
	dependencies {
		compile 'com.github.dlepex:jcext:v0.5'

		// compile 'org.jctools:jctools-core:2.1.1' // optional dependency, 
		// JCExt may use JCTools MpmcArrayQueue, if it detects its presence
	}
```
#### Overview

##### github.jcext.Enqueuer&lt;T&gt; https://dlepex.github.io/jcext/github/jcext/Enqueuer.html

Enqueuer is the most basic form of actor-like entity: it is the queue + associated asynchronous consumer aka  **Poller**.

All other actor-like entities in this lib are implemented on top of the Enqueuer. 

So if you want to read some code, read the code of this class first.



##### github.jcext.TaskEnqueuer https://dlepex.github.io/jcext/github/jcext/TaskEnqueuer.html

TaskEnqueuer is the Enqueuer with the predefined poller, which polls and executes async tasks one by one.

TaskEnqueuer can be used as the direct **replacement for the single-threaded ExecutorService**, in case your tasks are asynchronous computations.
Note that this class doesn't follow ExecutorService API deliberately because it can be misused for blocking tasks.


TaskEnqueuer guarantees that:
 - Async tasks will be executed in the order of their arrival
 - Async tasks will NEVER run concurrently i.e. next AsyncRunnable will wait for the completion of the CompletionStage of the previous AsyncRunnable



##### github.jcext.Agent https://dlepex.github.io/jcext/github/jcext/Agent.html

- **Agent** implements the specific form of "lock pattern" for async computations which
need to share a mutable state.
- The implementation is inspired by Elixir Agent module. It is rather trivial wrapper around the TaskEnqueuer class 
(which in turn is the wrapper around Enqueuer)

See https://hexdocs.pm/elixir/Agent.html



###### github.jcext.applications.ExactDateScheduler

Schedules task execution at specified LocalDateTime.
Tasks are executed sequentially one after another, their order is preserved in case of equal begin dates


#### Javadoc

https://dlepex.github.io/jcext/
