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

	private static final JcExtQueueProvider qp;

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
			qp = jcToolsPresent ?
					(JcExtQueueProvider) Class.forName("github.jcext.internal.JcToolsQueueProvider").newInstance()
					: new JcExtQueueProvider();


		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	static <T> Queue<T> newPreallocatedQueue(int cap) {
		return qp.newPreallocatedQueue(cap);
	}

	static boolean isUsingJcTools() {
		return qp.getClass() != JcExtQueueProvider.class;
	}

	public static <T> T with(T t, Consumer<? super T> scope) {
		scope.accept(t);
		return t;
	}
}
