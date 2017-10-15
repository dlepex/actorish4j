The following utility classes are provided by this small lib:



#### github.jcext.TaskQueue

This class lets the user to enqueue and execute async tasks and guarantees that they will run sequentially

TaskQueue can be used to implement such concurrent entities as Actors or Agents *effortlessly* 


#### github.jcext.Agent

Agents provide access to shared mutable state in async fashion.
Agents behave like locks for async computations. 
The implementation is inspired by Elixir Agents and is rather trivial wrapper around the TaskQueue class.


#### github.jcext.ExactDateScheduler

Schedules task execution at specified LocalDateTime.
Tasks are executed sequentially one after another, their order is preserved in case of equal begin dates