package github.jcext;

import com.google.common.base.Preconditions;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.jctools.queues.MpmcArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * TaskQueue lets the user to enqueue and execute async tasks and guarantees that they will run sequentially
 * TaskQueue can be used to implement such concurrent entities as Actors or Agents.
 */
@SuppressWarnings("WeakerAccess")
public final class TaskQueue { //TODO this class belongs generic utils package.
  /**
   * Task is considered completed when its CompletionStage is complete.
   */
  @FunctionalInterface
  public interface Task {
    /**
     * Must be non-blocking
     */
    CompletionStage<?> run();
  }

  @FunctionalInterface
  public interface TaskWithResult<V> {

    CompletableFuture<V> run();

  }

  private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);
  private static final Executor sameThreadExecutor = Runnable::run;

  private final MpmcArrayQueue<Task> queue;
  private final AtomicBoolean planned = new AtomicBoolean();
  private final Runnable queuePollRunnable;

  @SuppressWarnings("unchecked")
  private TaskQueue(Executor threadPool, int queueCap) {
    this.queue = new MpmcArrayQueue<>(queueCap);

    BiConsumer pollNextIfExists = (ignored, ignored2) -> {
      planned.set(false);
      if (queue.peek() != null) {
        planExecution(threadPool);
      }
    };
    this.queuePollRunnable = () -> runSafely(queue.poll()).whenComplete(pollNextIfExists);
  }

  public static TaskQueue create(int queueCap) {
    return create(ForkJoinPool.commonPool(), queueCap);
  }

  @SuppressWarnings("WeakerAccess")
  public static TaskQueue create(Executor exec, int queueCap) {
    Preconditions.checkArgument(queueCap > 0);
    return new TaskQueue(exec, queueCap);
  }

  /**
   * @see #tryEnqueue(Task)  if you want boolean result instead of RejectedExecutionException
   * @see #tryEnqueueWithResult(TaskWithResult)  if your task has some usefull result of its execution
   */
  public void enqueue(Task t) throws RejectedExecutionException {
    if (!tryEnqueue(t)) {
      throw new RejectedExecutionException("queue overflow");
    }
  }


  /**
   * @return false if queue overflow
   */
  @SuppressWarnings("WeakerAccess")
  public boolean tryEnqueue(Task t) {
    if (!queue.offer(t)) {
      return false;
    }
    planExecution(sameThreadExecutor);
    return true;
  }


  /**
   * @return Optional.empty if queue overflow.
   */
  @SuppressWarnings("WeakerAccess")
  public <V> Optional<CompletionStage<V>> tryEnqueueWithResult(TaskWithResult<V> twr) {
    CompletableFuture<V> result = new CompletableFuture<>();
    Task t = () -> {
      try {
        CompletionStage<V> twrResult = twr.run();
        twrResult.whenComplete((value, ex) -> {
          if (ex == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(ex);
          }
        });
        return twrResult;
      } catch (Exception e) {
        result.completeExceptionally(e);
        return DoneFuture;
      }
    };
    return tryEnqueue(t) ? Optional.of(result) : Optional.empty();
  }

  private void planExecution(Executor exec) {
    if (planned.compareAndSet(false, true)) {
      try {
        exec.execute(queuePollRunnable);
      } catch (Throwable err) {
        // this should never happen
        planned.set(false);
        log.error("uncaught underlying executor error", err);
      }
    }
  }

  private static final CompletableFuture<Void> DoneFuture = CompletableFuture.completedFuture(null);

  private static CompletionStage<?> runSafely(Task t) {
    try {
      return t.run();
    } catch (Exception e) {
      log.error("uncaught task error", e);
      return DoneFuture;
    }
  }
}
