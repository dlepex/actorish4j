import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.PriorityQueue;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;

/**
 * OrderedScheduler executes tasks sequentially one after another and keeps their order in case of equal dates
 */
public class OrderedScheduler {
    private static final Logger logger = LoggerFactory.getLogger(OrderedScheduler.class);

    @FunctionalInterface
    public interface Task {
        interface Info {
           LocalDateTime begin();
           LocalDateTime realBegin();
           long order();
           default Duration delay() {
               return Duration.between(begin(), realBegin());
           }
        }

        void run(Info info);

        static Task fromRunnable(Runnable r) {
            return info -> r.run();
        }
    }

    private final BlockingQueue<TaskStruct> inboundQueue;
    private final int plannedTasksLimit;

    /**
     * @param inboundQueueLimit the limit of inbound queue, if reached => RejectedExecutionException
     * @param plannedTasksLimit the limit of total tasks awaiting their execution
     * @param executor one thread of this executor is grabbed permanently // todo use thread fac
     * @return
     */
    public static OrderedScheduler create(int inboundQueueLimit, int plannedTasksLimit, Executor executor) {
        checkArgument(inboundQueueLimit > 0);
        checkArgument(plannedTasksLimit > 0);
        checkArgument(!(executor instanceof ForkJoinPool));
        return new OrderedScheduler(new ArrayBlockingQueue<>(inboundQueueLimit), plannedTasksLimit, executor);
    }

    private OrderedScheduler(BlockingQueue<TaskStruct> q, int plannedTasksLimit, Executor executor) {
        this.inboundQueue = q;
        this.plannedTasksLimit = plannedTasksLimit;
        executor.execute(this::pollingLoop);
    }

    public void schedule(LocalDateTime beginAt, Task task) throws RejectedExecutionException {
        checkNotNull(task);
        checkNotNull(beginAt);
        if(!inboundQueue.offer(new TaskStruct(beginAt, task))) {
            throw new RejectedExecutionException("OrderedScheduler is flooded");
        }
    }

    public void schedule(LocalDateTime beginAt, Runnable runnable) throws RejectedExecutionException {
        checkNotNull(runnable);
        checkNotNull(beginAt);
        schedule(beginAt, Task.fromRunnable(runnable));
    }

    public <T> CompletableFuture<T> schedule(LocalDateTime beginAt, Callable<T> callable)
            throws RejectedExecutionException  {
        checkNotNull(callable);
        checkNotNull(beginAt);
        CompletableFuture<T> result = new CompletableFuture<>();
        schedule(beginAt, asRunnable(callable, result, ForkJoinPool.commonPool()));
        return result;
    }

    private static <T>  Runnable asRunnable(Callable<T> callable, CompletableFuture<T> promise, Executor exec) {
        return () -> {
            try {
                try {
                    T result = callable.call();
                    exec.execute(() -> promise.complete(result));
                } catch (Throwable ex) {
                    exec.execute(() -> promise.completeExceptionally(ex));
                }
            } catch (Throwable ex) {
                // this should never happen with FJP
                logger.error("Executor rejects us. Can't execute promise completion", ex);
            }
        };
    }

    private static final class TaskStruct implements Comparable<TaskStruct>, Task.Info {
        final LocalDateTime begin;
        final Task t;
        long order;
        LocalDateTime realBegin;

        TaskStruct(LocalDateTime begin, Task t) {
            this.begin = begin;
            this.t = t;
        }

        @Override
        public int compareTo(TaskStruct other) {
            int cmp = begin.compareTo(other.begin);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(order, other.order);
        }

        void runSafely() {
            try {
                t.run(this);
            } catch (Throwable ex) {
                logger.warn("uncaught task error", ex);
            }
        }

        @Override
        public LocalDateTime begin() {
            return begin;
        }

        @Override
        public LocalDateTime realBegin() {
            return realBegin;
        }

        @Override
        public long order() {
            return order;
        }
    }

    //<editor-fold desc="Scheduler thread state variables:">
    private long taskCounter;
    private final PriorityQueue<TaskStruct> plannedQueue =  new PriorityQueue<>(512 );
    private long pollTimeout; // in millis
    //</editor-fold>

    private void doPoll() throws InterruptedException {

       {
            TaskStruct task = pollTimeout > 0 ? inboundQueue.poll(pollTimeout, TimeUnit.MILLISECONDS) : inboundQueue.poll();
            if (task != null) {
                if (plannedQueue.size() < plannedTasksLimit) {
                    task.order = taskCounter++;
                    plannedQueue.offer(task);
                } else {
                    logger.warn("plannedQueue exceeded its limit={}", plannedTasksLimit);
                }
            }
        }

        for(;;) {
            TaskStruct task = plannedQueue.peek();
            if (task == null) {
                pollTimeout = 0;
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            pollTimeout = Duration.between(now, task.begin).toMillis();
            if (pollTimeout > 0) {
                return;
            }

            TaskStruct ignored = plannedQueue.poll();
            assert ignored == task;
            task.realBegin = now;
            task.runSafely();
        }
    }

    private void pollingLoop() {
        try {
            for (;;) {
                doPoll();
            }
        } catch (InterruptedException ex) {
            // caught interrupted => we're done
            //todo proper reliable shutdown
        } catch (Exception ex) {
            logger.error("doPoll() may throw only interrupted", ex);
        }
    }
}
