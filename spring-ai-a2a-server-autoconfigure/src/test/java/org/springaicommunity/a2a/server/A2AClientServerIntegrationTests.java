/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.a2a.server;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.springaicommunity.a2a.server.executor.DefaultAgentExecutor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for A2A server auto-configuration on the servlet web stack (WebMvc.fn
 * functional routes).
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>A2A server starts successfully with auto-configuration</li>
 * <li>Default AgentCard bean is created with correct configuration</li>
 * <li>HTTP endpoints are available and behave per the A2A protocol</li>
 * </ul>
 *
 * @author Ilayaperumal Gopinathan
 * @author Mekki Amiri
 * @since 0.1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class A2AClientServerIntegrationTests {

	/**
	 * Minimal Spring Boot application for testing auto-configuration.
	 */
	@SpringBootApplication
	static class TestApplication {

		public static void main(String[] args) {
			SpringApplication.run(TestApplication.class, args);
		}

	}

	@TestConfiguration
	static class TestConfig {

		/**
		 * Provides a minimal ChatClient bean to trigger auto-configuration.
		 */
		@Bean
		public ChatClient testChatClient(ChatModel chatModel) {
			return ChatClient.builder(chatModel).defaultSystem("You are a test agent").build();
		}

		/**
		 * Provides AgentCard bean for testing.
		 */
		@Bean
		public AgentCard testAgentCard() {
			return new AgentCard("Spring AI A2A Agent", "A2A agent powered by Spring AI", "http://localhost:58888/a2a",
					null, "1.0.0", null, new io.a2a.spec.AgentCapabilities(false, false, false, List.of()),
					List.of("text"), List.of("text"), List.of(), false, null, null, null,
					List.of(new io.a2a.spec.AgentInterface("JSONRPC", "http://localhost:58888/a2a")), "JSONRPC",
					"0.1.0", null);
		}

		/**
		 * Provides a test AgentExecutor bean.
		 */
		@Bean
		public AgentExecutor testAgentExecutor(ChatClient testChatClient) {
			return new DefaultAgentExecutor(testChatClient, (chatClient, requestContext) -> {
				return DefaultAgentExecutor.extractTextFromMessage(requestContext.getMessage());
			}) {
			};
		}

	}

	@LocalServerPort
	private int port;

	@Autowired
	private AgentCard agentCard;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private WebTestClient client() {
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	/**
	 * Tests that the A2A server started successfully with default auto-configuration.
	 */
	@Test
	void testA2AServerStarted() {
		assertThat(this.agentCard).isNotNull();
		assertThat(this.agentCard.name()).isEqualTo("Spring AI A2A Agent");
		assertThat(this.agentCard.description()).isEqualTo("A2A agent powered by Spring AI");
		assertThat(this.agentCard.version()).isEqualTo("1.0.0");
		assertThat(this.agentCard.protocolVersion()).isEqualTo("0.1.0");
	}

	/**
	 * Tests that the server is running on the expected port.
	 */
	@Test
	void testServerPort() {
		assertThat(this.port).isEqualTo(58888);
	}

	/**
	 * Tests that default AgentCard capabilities are configured.
	 */
	@Test
	void testAgentCardCapabilities() {
		assertThat(this.agentCard.capabilities()).isNotNull();
		assertThat(this.agentCard.capabilities().streaming()).isFalse();
		assertThat(this.agentCard.capabilities().pushNotifications()).isFalse();
	}

	/**
	 * Tests that the agent card is served over HTTP at the well-known location.
	 */
	@Test
	void testAgentCardEndpoint() {
		client().get()
			.uri("/.well-known/agent-card.json")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody(String.class)
			.value(body -> assertThat(body).contains("Spring AI A2A Agent"));
	}

	/**
	 * Tests the full message/send round-trip and task retrieval over HTTP.
	 */
	@Test
	void testSendMessageAndGetTask() throws Exception {
		Message message = new Message.Builder().role(Message.Role.USER)
			.parts(new TextPart("hello a2a"))
			.messageId("msg-1")
			.build();
		SendMessageRequest request = new SendMessageRequest("req-1", new MessageSendParams(message, null, null));

		byte[] responseBody = client().post()
			.uri("/")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentType(MediaType.APPLICATION_JSON)
			.expectBody()
			.returnResult()
			.getResponseBody();

		JsonNode response = this.objectMapper.readTree(responseBody);
		assertThat(response.path("id").asText()).isEqualTo("req-1");
		JsonNode result = response.path("result");
		assertThat(result.path("kind").asText()).isEqualTo("task");
		assertThat(result.path("status").path("state").asText()).isEqualTo("completed");

		String taskId = result.path("id").asText();
		client().get()
			.uri("/tasks/{taskId}", taskId)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.id")
			.isEqualTo(taskId);
	}

	/**
	 * Tests the message/stream SSE endpoint: an Accept: text/event-stream request must
	 * yield an ordered event stream ending with a completed task.
	 */
	@Test
	void testStreamMessage() throws Exception {
		Message message = new Message.Builder().role(Message.Role.USER)
			.parts(new TextPart("hello streaming a2a"))
			.messageId("msg-stream-1")
			.build();
		SendStreamingMessageRequest request = new SendStreamingMessageRequest("req-stream-1",
				new MessageSendParams(message, null, null));

		List<String> events = client().post()
			.uri("/")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class)
			.getResponseBody()
			.collectList()
			.block(Duration.ofSeconds(30));

		assertThat(events).isNotEmpty();
		JsonNode firstEvent = this.objectMapper.readTree(events.get(0));
		assertThat(firstEvent.path("id").asText()).isEqualTo("req-stream-1");
		assertThat(firstEvent.has("result")).isTrue();
		assertThat(String.join("\n", events)).contains("completed");
	}

}
