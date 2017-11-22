package io.github.actorish4j.internal;


import org.jctools.queues.MpmcArrayQueue;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class JcToolsQueueProvider extends ActorishUtil.QueueProvider {

	public <T> Queue<T> newPreallocatedQueue(int cap) {
		return new JcToolsQueue<>(new MpmcArrayQueue<>(cap));
	}

	/**
	 * This class delegates all methods to MpmcArrayQueue, except isEmpty() which was redefined in terms of peek(). <p>
	 * The reason is that {@code peek()==null} was tested and works as intended, but {@link MpmcArrayQueue#isEmpty()} may be too imprecise for our needs.
 	 */
	private final class JcToolsQueue<E> extends AbstractQueue<E> {

		private final MpmcArrayQueue<E> q;


		private JcToolsQueue(MpmcArrayQueue<E> q) {
			this.q = q;
		}

		@Override
		public boolean offer(E e) {
			return q.offer(e);
		}

		@Override
		public E poll() {
			return q.poll();
		}

		@Override
		public E peek() {
			return q.peek();
		}

		@Override
		public Iterator<E> iterator() {
			return q.iterator();
		}

		@Override
		public String toString() {
			return q.toString();
		}

		@Override
		public void clear() {
			q.clear();
		}

		@Override
		public boolean add(E e) {
			return q.add(e);
		}

		@Override
		public E remove() {
			return q.remove();
		}

		@Override
		public E element() {
			return q.element();
		}

		@Override
		public boolean addAll(Collection<? extends E> c) {
			return q.addAll(c);
		}

		@Override
		public int size() {
			return q.size();
		}

		@Override
		public boolean isEmpty() {
			return q.peek() == null;
		}

		@Override
		public boolean contains(Object o) {
			return q.contains(o);
		}

		@Override
		public Object[] toArray() {
			return q.toArray();
		}

		@Override
		public <T> T[] toArray(T[] a) {
			return q.toArray(a);
		}

		@Override
		public boolean remove(Object o) {
			return q.remove(o);
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			return q.containsAll(c);
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			return q.removeAll(c);
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			return q.retainAll(c);
		}

		@Override
		public boolean removeIf(Predicate<? super E> filter) {
			return q.removeIf(filter);
		}

		@Override
		public boolean equals(Object o) {
			return q.equals(o);
		}

		@Override
		public int hashCode() {
			return q.hashCode();
		}

		@Override
		public Spliterator<E> spliterator() {
			return q.spliterator();
		}

		@Override
		public Stream<E> stream() {
			return q.stream();
		}

		@Override
		public Stream<E> parallelStream() {
			return q.parallelStream();
		}

		@Override
		public void forEach(Consumer<? super E> action) {
			q.forEach(action);
		}
	}

}
