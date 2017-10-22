package github.jcext.internal;


import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public class JcExtQueueProvider {

	public <T> Queue<T> createBoundedQueue(int cap) {
		return new ArrayBlockingQueue<>(cap);
	}
}
