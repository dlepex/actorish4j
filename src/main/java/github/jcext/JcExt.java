package github.jcext;


import github.jcext.internal.JcExtQueueProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


public final class JcExt {
	private static final Logger log = LoggerFactory.getLogger(TaskEnqueuer.class);
	static final CompletableFuture<Void> doneFuture = CompletableFuture.completedFuture(null);

	private JcExt() {
	}

	private static final JcExtQueueProvider queueProvider;

	static final boolean jcToolsFound;

	static {
		boolean jcToolsPresent = false;
		try {
			Class.forName("org.jctools.queues.MpmcArrayQueue");
			jcToolsPresent = true;
			log.trace("jctools detected");
		} catch (ClassNotFoundException e) {
			// ignored
		}
		try {
			queueProvider = jcToolsPresent ?
					(JcExtQueueProvider) Class.forName("github.jcext.internal.JcToolsQueueProvider").newInstance()
					: new JcExtQueueProvider();


		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}

		jcToolsFound = jcToolsPresent;
	}

	static <T> Queue<T> newPreallocatedQueue(int cap) {
		return queueProvider.newPreallocatedQueue(cap);
	}


	public static <T> T with(T t, Consumer<? super T> scope) {
		scope.accept(t);
		return t;
	}

	/**
	 * Polls until q.poll() returns null or max elements reached.
	 */
	public static <T> void pollMany(int maxElements, Queue<? extends T> q, Consumer<? super T> c) {
		assert maxElements > 0;
		for(T item = q.poll(); item != null && maxElements > 0; -- maxElements) {
			c.accept(item);
		}
	}



}
