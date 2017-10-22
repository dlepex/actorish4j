package github.jcext.internal;


import org.jctools.queues.MpmcArrayQueue;

import java.util.Queue;

public final class JcToolsQueueProvider extends JcExtQueueProvider {

	public <T> Queue<T> createBoundedQueue(int cap) {
		return new MpmcArrayQueue<>(cap);
	}
}
