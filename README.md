#### Purpose

This small lib provides concurrent, asynchronous, statefull (actor-like) entities: Enqueuer, TaskEnqueuer and Agent.
 
Due to its simplicity and **tight integration with Java 8 CompletionStage and Queue API**, JCExt might be  better choice 
than some bloated actor frameworks which are trying to imitate all aspects of Erlang actors, introducing a lot of new API 
and extra cognitive load.

Simplest way to use JCExt in your project is thru https://jitpack.io

For gradle build: 
```groovy
	dependencies {
		compile 'com.github.dlepex:jcext:v0.7'

		// compile 'org.jctools:jctools-core:2.1.1' // optional dependency, 
		// JCExt may use JCTools MpmcArrayQueue, if it detects its presence
	}
```
#### Overview


##### github.jcext.Enqueuer&lt;T&gt; https://dlepex.github.io/jcext/github/jcext/Enqueuer.html

Enqueuer is the most basic (and the most useful) form of actor-like entity. 

Enqueuer implements multiple-producer single-consumer pattern, anyone can offer message to the Enqueuer, but only
single consumer can read (poll) the queue.

All other actor-like entities in this lib are implemented on top of the Enqueuer. 

##### github.jcext.TaskEnqueuer https://dlepex.github.io/jcext/github/jcext/TaskEnqueuer.html

TaskEnqueuer is the Enqueuer with the predefined poller, which polls and executes async tasks one by one.

TaskEnqueuer can be used as the direct **replacement for the single-threaded ExecutorService**, in case your tasks are asynchronous computations.
Note that this class doesn't follow ExecutorService API deliberately because it can be misused for blocking tasks.


TaskEnqueuer guarantees that:
 - Async tasks will be executed in the order of their arrival
 - Async tasks will NEVER run concurrently i.e. next AsyncRunnable will wait for the completion of the CompletionStage of the previous AsyncRunnable



##### github.jcext.Agent https://dlepex.github.io/jcext/github/jcext/Agent.html

- **Agent** implements the specific form of "locking pattern" for async computations which
need to share a mutable state.
- The implementation is inspired by Elixir Agent module. It is rather trivial wrapper around the TaskEnqueuer class 
(which in turn is the wrapper around Enqueuer)

See https://hexdocs.pm/elixir/Agent.html


#### Javadoc

https://dlepex.github.io/jcext/

#### Showcases

The classes below are the examples of applying JCExt to the "real world problems". 

###### NettyChannelWritesEnqueuer

https://github.com/dlepex/jcext/blob/master/src/main/java/github/jcext/applications/NettyChannelWritesEnqueuer.java

NettyChannelWritesEnqueuer orders writes to Netty channel and flush them when it's appropriate to do so.

###### ExactDateScheduler

https://github.com/dlepex/jcext/blob/master/src/main/java/github/jcext/applications/ExactDateScheduler.java

Schedules task execution at specified LocalDateTime.
Tasks are executed sequentially one after another, their order is preserved in case of equal begin dates


