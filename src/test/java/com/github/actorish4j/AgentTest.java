package com.github.actorish4j;

import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;

import static org.testng.Assert.assertEquals;


public class AgentTest {

	@Test
	public void testVariousMethods() throws Exception {
		String id = "Bond";
		Agent<String> agent = new Agent<>("", c -> {
			c.setBoundedQueue(50_000);
			c.setAssociatedId(id);
		});
		assertEquals(agent.get().toCompletableFuture().get(), "");
		agent.update(s -> s + "x");
		assertEquals(agent.get().toCompletableFuture().get(), "x");
		assertEquals(agent.getAndUpdateAsync(s ->
				CompletableFuture.completedFuture(new Agent.StateValuePair<>("hello", "zzz")))
				.toCompletableFuture().get(), "zzz");
		assertEquals(agent.get().toCompletableFuture().get(), "hello");
		assertEquals(agent.get(st -> st.substring(0, 1)).toCompletableFuture().get(), "h");
		assertEquals(agent.associatedId(), id);
	}


}