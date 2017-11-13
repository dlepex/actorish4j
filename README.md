### Purpose

This small library provides concurrent, asynchronous/non-blocking, stateful (in other words actor-like) entities.
 
Due to its simplicity and **tight integration with Java 8 CompletionStage (CompletableFuture) and Queue API**, 
JCExt can be  better choice than some bloated Actor frameworks. If you are not interested in supervision hierarchies, 
actor-based networking and only need the concurrency part then JCExt may be the tool for you.

The simplest way to use JCExt in your project is through https://jitpack.io

Gradle build sample: 
```groovy
	dependencies {
		compile 'com.github.dlepex:jcext:v0.7'

		// compile 'org.jctools:jctools-core:2.1.1' // optional dependency, 
		// JCExt may use JCTools MpmcArrayQueue, if it detects its presence
	}
```
### Overview


#### github.jcext.Enqueuer&lt;T&gt; 
https://dlepex.github.io/jcext/github/jcext/Enqueuer.html

Enqueuer is the most basic (and the most useful) form of actor-like entity. 

Enqueuer implements the multiple-producer single-consumer pattern: anyone can offer message to the Enqueuer, but only
the single consumer can read (poll) the queue.

All other actor-like entities in this lib are implemented on top of the Enqueuer. 

#### github.jcext.TaskEnqueuer 
https://dlepex.github.io/jcext/github/jcext/TaskEnqueuer.html

TaskEnqueuer is the Enqueuer which polls and executes async tasks one by one.

TaskEnqueuer can be used as the direct **replacement for the single-threaded ExecutorService**, in case your tasks are asynchronous computations.
Note that this class doesn't follow ExecutorService API deliberately because it can be misused for blocking tasks.


TaskEnqueuer guarantees that:
 - Async tasks will be executed in the order of their arrival
 - Async tasks will NEVER run concurrently i.e. next AsyncRunnable will wait for the completion of the CompletionStage of the previous AsyncRunnable



#### github.jcext.Agent&lt;S&gt; 
https://dlepex.github.io/jcext/github/jcext/Agent.html

Agent implements the specific form of "state sharing pattern" in the context of asynchronous computations.

Agent is a very simple (thin) wrapper around the TaskEnqueuer class.

Agent interface (method naming) is inspired by Elixir Agent module. 
See https://hexdocs.pm/elixir/Agent.html

#### github.jcext.StateMachine&lt;E&gt; 

https://dlepex.github.io/jcext/github/jcext/StateMachine.html

Experimental implementation of Erlang gen_statem (gen_fsm) behaviour in a "state functions mode". In this mode
each state has a corresponding function (StateFunc) that handles events in that state.

This test contains two example of state machines:
https://github.com/dlepex/jcext/blob/master/src/main/java/github/jcext/StateMachineTest.java 


#### Javadoc

https://dlepex.github.io/jcext/

#### Showcases

The classes below are the examples of applying JCExt to the "real world problems". 

###### NettyChannelWritesEnqueuer

https://github.com/dlepex/jcext/blob/master/src/main/java/github/jcext/applications/NettyChannelWritesEnqueuer.java

NettyChannelWritesEnqueuer orders writes to Netty channel and flushes them when it's appropriate to do so.

###### ExactDateScheduler

https://github.com/dlepex/jcext/blob/master/src/main/java/github/jcext/applications/ExactDateScheduler.java

ExactDateScheduler schedules task execution at specified LocalDateTime.
Tasks are executed sequentially one after another, their order is preserved in case of equal begin dates


