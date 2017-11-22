package com.github.actorish4j;

import com.github.actorish4j.internal.ActorishUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class StateMachineTest {


	@Test
	public void testDoorWithCodeLock() throws Exception {
		DoorWithCodeLock door = new DoorWithCodeLock("12345");
		door.pressButton('1');
		door.pressButton('5');
		door.pressButton('1');
		door.pressButton('2');
		Assert.assertTrue(door.isLocked());
		door.pressButton('3');
		door.pressButton('4');
		door.pressButton('5');

		door.pressButton('6');
		Thread.sleep(200);
		door.pressButton('7');
		Assert.assertTrue(!door.isLocked());
		Thread.sleep(2000);
		Assert.assertTrue(door.isLocked());
	}

	@Test
	public void testFinalState() throws Exception {
		Random r = ThreadLocalRandom.current();
		RngJumper j = new RngJumper();
		Assert.assertTrue(r.ints(1, 3).filter(v -> {
			j.send(v);
			return j.finalStateReached().toCompletableFuture().isDone();
		}).findFirst().isPresent());

		j.send(100);
		j.send(200);
		Thread.sleep(10);
	}


	static final class DoorWithCodeLock extends StateMachine<DoorWithCodeLock.Event> {
		private static final Logger log = LoggerFactory.getLogger(DoorWithCodeLock.class);
		private final String code;

		DoorWithCodeLock(String code) {
			super(new Conf());
			this.code = code;
		}

		@Override
		protected StateFunc<Event> initialState() {
			return locked(0);
		}

		StateFunc<Event> locked(int i) {
			return ev -> locked(ev, i);
		}

		NextState locked(Event ev, int index) {
			if (code.charAt(index) == ev.button) {
				boolean isLast = index == code.length() - 1;
				if (!isLast) {
					return goTo(locked(index + 1));
				} else {
					return goTo(openDoorAction().thenRun(this::scheduleLockDoor).thenApply(ignored -> this::open));
				}
			}
			return goTo(locked(0));
		}

		NextState open(Event ev) {
			if (ev.lockDoor) {
				return goTo(lockDoorAction().thenApply(ignored -> locked(0)));
			}
			return goTo(sameState());
		}

		private CompletionStage<?> openDoorAction() {
			log.info("unlocking door");
			isLocked.set(false);
			return CompletableFuture.completedFuture(null);
		}

		private CompletionStage<?> lockDoorAction() {
			log.info("locking door");
			isLocked.set(true);
			return CompletableFuture.completedFuture(null);
		}

		AtomicBoolean isLocked = new AtomicBoolean(true);

		boolean isLocked() {
			return isLocked.get();
		}

		public void pressButton(char button) {
			  send(new Event(button, false));
		}

		private void scheduleLockDoor() {
			timer.delay(() -> send(new Event('\0', true)), 1, TimeUnit.SECONDS);
		}

		/**
		 * This is a "type union" of two events: code button pressed & lockDoor door.
		 */
		static class Event {
			char button;
			boolean lockDoor;

			Event(char button, boolean lockDoor) {
				this.button = button;
				this.lockDoor = lockDoor;
			}
		}
	}


	static final class RngJumper extends StateMachine<Integer> {

		protected RngJumper() {
			super(ActorishUtil.with(new Conf(), c -> c.setAssociatedId("1")));
		}


		public void send(Integer i) {
			super.send(i);
		}

		@Override
		protected StateFunc<Integer> initialState() {
			return this::s1;
		}

		NextState s1(Integer ev) {
			System.out.println("s1");
			switch(ev) {
				case 1: return goTo(sameState());
				default: return goTo(this::s2, 10);
			}
		}

		NextState s2(Integer ev) {
			System.out.println("s2");
			switch(ev) {
				case 1: return goTo(sameState());
				default: return goTo(this::s3, 20);

			}
		}

		NextState s3(Integer ev) {
			System.out.println("s3");
			switch(ev) {
				case 1: return goTo(this::s1);
				default: return goTo(finalState(), 20);
			}
		}
	}


}