/*
 * Copyright 2013-2020 the original author or authors.
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
package org.springframework.hateoas.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.client.Traverson.TraversalBuilder;
import org.springframework.hateoas.mediatype.hal.Jackson2HalModule;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.MimeType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static net.jadler.Jadler.verifyThatRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.hateoas.client.Hop.rel;

/**
 * Integration tests for {@link Traverson}.
 *
 * @author Oliver Gierke
 * @author Greg Turnquist
 * @author Michael Wirth
 * @since 0.11
 */
class ReactiveTraversonTest {

	static URI baseUri;
	static Server server;

	ReactiveTraverson reactiveTraverson;
	Traverson traverson;

	@BeforeAll
	public static void setUpClass() {

		server = new Server();
		baseUri = URI.create(server.rootResource());

		setUpActors();
	}

	@BeforeEach
	void setUp() {
		this.reactiveTraverson = new ReactiveTraverson(WebClient.create(), baseUri, MediaTypes.HAL_JSON);
		this.traverson = new Traverson(baseUri, MediaTypes.HAL_JSON);

	}

	@AfterAll
	public static void tearDown() throws IOException {

		if (server != null) {
			server.close();
		}
	}

	/**
	 * @see #131
	 */
	@Test
	void rejectsNullBaseUri() {

		assertThatIllegalArgumentException().isThrownBy(() -> {
			new ReactiveTraverson(WebClient.create(), null, MediaTypes.HAL_JSON);
		});
	}

	/**
	 * @see #131
	 */
	@Test
	void rejectsEmptyMediaTypes() {

		assertThatIllegalArgumentException().isThrownBy(() -> {
			new ReactiveTraverson(WebClient.create(), baseUri);
		});
	}

	/**
	 * @see #131
	 */
	@Test
	void sendsConfiguredMediaTypesInAcceptHeader() {
		StepVerifier.create(reactiveTraverson.follow()
			.retrieve()
			.flatMap(spec -> spec.bodyToMono(String.class)))
			.expectNextCount(1)
			.verifyComplete();

		verifyThatRequest()
				.havingPathEqualTo("/")
				.havingHeader("Accept", contains(MediaTypes.HAL_JSON_VALUE))
				.receivedOnce();
	}

	/**
	 * @see #131
	 */
	@Test
	void readsTraversalIntoJsonPathExpression() {
		StepVerifier.create(reactiveTraverson.follow("movies", "movie", "actor")
				.retrieve()
				.flatMap(spec -> spec.bodyToMono(String.class))
				.map(body -> JsonPath.read(body, "$.name")))
			.expectNext("Keanu Reaves")
			.verifyComplete();
	}

	/**
	 * @see #131
	 */
	@Test
	void readsJsonPathTraversalIntoJsonPathExpression() {
		StepVerifier.create(reactiveTraverson.follow(
				"$._links.movies.href", //
				"$._links.movie.href", //
				"$._links.actor.href")
			.retrieve()
			.flatMap(spec -> spec.bodyToMono(String.class))
			.map(body -> JsonPath.read(body, "$.name")))
			.expectNext("Keanu Reaves")
			.verifyComplete();
	}

	/**
	 * @see #131
	 */
	@Test
	void readsTraversalIntoResourceInstance() {

		ParameterizedTypeReference<EntityModel<Actor>> typeReference = new ParameterizedTypeReference<EntityModel<Actor>>() {};
		StepVerifier.create(reactiveTraverson.follow("movies", "movie", "actor")
			.retrieve()
			.flatMap(spec -> spec.bodyToMono(typeReference))
			.map(EntityModel::getContent)
			.map(Actor::getName))
			.expectNext("Keanu Reaves")
			.verifyComplete();
	}

	/**
	 * @see #187
	 */
	@Test
	void sendsConfiguredHeadersForJsonPathExpression() {

		String expectedHeader = "<https://www.example.com>;rel=\"home\"";

		HttpHeaders headers = new HttpHeaders();
		headers.add("Link", expectedHeader);

		StepVerifier.create(reactiveTraverson.follow("movies", "movie", "actor")
			.withHeaders(headers)
			.retrieve()
			.flatMap(spec -> spec.bodyToMono(String.class))
			.map(body -> JsonPath.read(body, "$.name")))
			.expectNext("Keanu Reaves")
			.verifyComplete();

		verifyThatRequest() //
				.havingPath(startsWith("/actors/")) //
				.havingHeader("Link", hasItem(expectedHeader))
				.receivedOnce();
	}

	/**
	 * @see #187
	 */
	@Test
	void sendsConfiguredHeadersForToEntity() {

		String expectedHeader = "<https://www.example.com>;rel=\"home\"";

		HttpHeaders headers = new HttpHeaders();
		headers.add("Link", expectedHeader);

		StepVerifier.create(reactiveTraverson.follow("movies", "movie", "actor")
			.withHeaders(headers)
			.retrieve()
			.flatMap(spec -> spec.toEntity(Actor.class)))
			.expectNextCount(1)
			.verifyComplete();

		verifyThatRequest() //
				.havingPath(startsWith("/actors/")) //
				.havingHeader("Link", hasItem(expectedHeader))
				.receivedOnce();
	}

	/**
	 * @see #185
	 */
	@Test
	void usesCustomLinkDiscoverer() {

		this.reactiveTraverson = new ReactiveTraverson(
			WebClient.create(), URI.create(server.rootResource() + "/github"), MediaType.APPLICATION_JSON);
		this.reactiveTraverson.setLinkDiscoverers(Arrays.asList(new GitHubLinkDiscoverer()));

		StepVerifier.create(this.reactiveTraverson.follow("foo")
				.retrieve()
				.flatMap(spec -> spec.bodyToMono(String.class)
				.map(body -> JsonPath.read(body, "$.key"))))
			.expectNext("value")
			.verifyComplete();
	}

	/**
	 * @see #212
	 */
	@Test
	void shouldReturnLastLinkFound() {

		StepVerifier.create(reactiveTraverson.follow("movies")
			.asLink())
			.assertNext(link -> {
				assertThat(link.getHref()).endsWith("/movies");
				assertThat(link.hasRel("movies")).isTrue();
			})
			.verifyComplete();
	}

	/**
	 * @see #307
	 */
	@Test
	void returnsTemplatedLinkIfRequested() {

		ReactiveTraverson.TraversalBuilder follow = new ReactiveTraverson(
			WebClient.create(), URI.create(server.rootResource().concat("/link")), MediaTypes.HAL_JSON)
				.follow("self");

		StepVerifier.create(follow.asTemplatedLink())
			.assertNext(link -> {
				assertThat(link.isTemplated()).isTrue();
				assertThat(link.getVariableNames()).contains("template");
			})
			.verifyComplete();

		StepVerifier.create(follow.asLink())
			.assertNext(link -> assertThat(link.isTemplated()).isFalse())
			.verifyComplete();
	}

	@Test // #971
	void returnsTemplatedRequiredLinkIfRequested() {

		StepVerifier.create(new ReactiveTraverson(
			WebClient.create(), URI.create(server.rootResource() + "/github-with-template"), MediaTypes.HAL_JSON) //
				.follow("rel_to_templated_link") //
				.asTemplatedLink())
			.assertNext(templatedLink -> {
				assertThat(templatedLink.isTemplated()).isTrue();
				assertThat(templatedLink.getVariableNames()).contains("issue");

				Link expandedLink = templatedLink.expand("42");

				assertThat(expandedLink.isTemplated()).isFalse();
				assertThat(expandedLink.getHref()).isEqualTo("/github/42");
			})
		.verifyComplete();
	}

	/**
	 * @see #346
	 */
	@Test
	void chainMultipleFollowOperations() {

		ParameterizedTypeReference<EntityModel<Actor>> typeReference = new ParameterizedTypeReference<EntityModel<Actor>>() {};
		StepVerifier.create(reactiveTraverson.follow("movies").follow("movie").follow("actor")
				.retrieve()
				.flatMap(spec -> spec.bodyToMono(typeReference))
				.map(EntityModel::getContent)
				.map(Actor::getName))
			.expectNext("Keanu Reaves")
			.verifyComplete();
	}

	/**
	 * @see #346
	 */
	@Test
	void allowAlteringTheDetailsOfASingleHop() {
		MimeType mimeType = MimeType.valueOf("application/hal+json");
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new Jackson2HalModule());
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		WebClient webClient = WebClient.builder()
			.codecs(configurer -> {
				configurer.customCodecs().registerWithDefaultConfig(new Jackson2JsonEncoder(mapper, mimeType));
				configurer.customCodecs().registerWithDefaultConfig(new Jackson2JsonDecoder(mapper, mimeType));
			}).build();

		this.reactiveTraverson = new ReactiveTraverson(
				webClient, URI.create(server.rootResource() + "/springagram"), MediaTypes.HAL_JSON);

		// tag::hop-with-param[]
		ParameterizedTypeReference<EntityModel<Item>> resourceParameterizedTypeReference = new ParameterizedTypeReference<EntityModel<Item>>() {};

		StepVerifier.create(reactiveTraverson
			.follow(rel("items").withParameter("projection", "noImages"))
			.follow("$._embedded.items[0]._links.self.href")
			.retrieve()
			.flatMap(spec -> spec.bodyToMono(resourceParameterizedTypeReference)))
			.assertNext(itemResource -> {
				assertThat(itemResource.hasLink("self")).isTrue();
				assertThat(itemResource.getRequiredLink("self").expand().getHref())
					.isEqualTo(server.rootResource() + "/springagram/items/1");

				final Item item = itemResource.getContent();
				assertThat(item.image).isEqualTo(server.rootResource() + "/springagram/file/cat");
				assertThat(item.description).isEqualTo("cat");
			})
			.verifyComplete();
	}

	/**
	 * @see #346
	 */
	@Test
	void allowAlteringTheDetailsOfASingleHopByMapOperations() {

		this.reactiveTraverson = new ReactiveTraverson(WebClient.create(), URI.create(server.rootResource() + "/springagram"), MediaTypes.HAL_JSON);

		// tag::hop-put[]
		ParameterizedTypeReference<EntityModel<Item>> resourceParameterizedTypeReference = new ParameterizedTypeReference<EntityModel<Item>>() {};

		Map<String, Object> params = Collections.singletonMap("projection", "noImages");

		EntityModel<Item> itemResource = traverson.//
				follow(rel("items").withParameters(params)).//
				follow("$._embedded.items[0]._links.self.href").//
				toObject(resourceParameterizedTypeReference);
		// end::hop-put[]

		assertThat(itemResource.hasLink("self")).isTrue();
		assertThat(itemResource.getRequiredLink("self").expand().getHref())
				.isEqualTo(server.rootResource() + "/springagram/items/1");

		final Item item = itemResource.getContent();
		assertThat(item.image).isEqualTo(server.rootResource() + "/springagram/file/cat");
		assertThat(item.description).isEqualTo("cat");
	}

	/**
	 * @see #346
	 */
	@Test
	void allowGlobalsToImpactSingleHops() {

		this.traverson = new Traverson(URI.create(server.rootResource() + "/springagram"), MediaTypes.HAL_JSON);

		Map<String, Object> params = new HashMap<>();
		params.put("projection", "thisShouldGetOverwrittenByLocalHop");

		ParameterizedTypeReference<EntityModel<Item>> resourceParameterizedTypeReference = new ParameterizedTypeReference<EntityModel<Item>>() {};
		EntityModel<Item> itemResource = traverson.follow(rel("items").withParameter("projection", "noImages"))
				.follow("$._embedded.items[0]._links.self.href") // retrieve first Item in the collection
				.withTemplateParameters(params).toObject(resourceParameterizedTypeReference);

		assertThat(itemResource.hasLink("self")).isTrue();
		assertThat(itemResource.getRequiredLink("self").expand().getHref())
				.isEqualTo(server.rootResource() + "/springagram/items/1");

		final Item item = itemResource.getContent();
		assertThat(item.image).isEqualTo(server.rootResource() + "/springagram/file/cat");
		assertThat(item.description).isEqualTo("cat");
	}

	/**
	 * @see #337
	 */
	@Test
	void doesNotDoubleEncodeURI() {

		this.traverson = new Traverson(URI.create(server.rootResource() + "/springagram"), MediaTypes.HAL_JSON);

		EntityModel<?> itemResource = traverson.//
				follow(rel("items").withParameters(Collections.singletonMap("projection", "no images"))).//
				toObject(EntityModel.class);

		assertThat(itemResource.hasLink("self")).isTrue();
		assertThat(itemResource.getRequiredLink("self").expand().getHref())
				.isEqualTo(server.rootResource() + "/springagram/items");
	}

	@Test
	void customHeaders() {

		String customHeaderName = "X-CustomHeader";

		traverson
				.follow(rel("movies").header(customHeaderName, "alpha").header(HttpHeaders.LOCATION,
						"http://localhost:8080/my/custom/location"))
				.follow(rel("movie").header(customHeaderName, "bravo")).follow(rel("actor").header(customHeaderName, "charlie"))
				.toObject("$.name");

		verifyThatRequest() //
				.havingPathEqualTo("/") //
				.havingHeader(HttpHeaders.ACCEPT, contains(MediaTypes.HAL_JSON_VALUE)); //

		verifyThatRequest().havingPathEqualTo("/movies") // aggregate root movies
				.havingHeader(HttpHeaders.ACCEPT, contains(MediaTypes.HAL_JSON_VALUE)) //
				.havingHeader(customHeaderName, contains("alpha")) //
				.havingHeader(HttpHeaders.LOCATION, contains("http://localhost:8080/my/custom/location")); //

		verifyThatRequest().havingPath(startsWith("/movies/")) // single movie
				.havingHeader(HttpHeaders.ACCEPT, contains(MediaTypes.HAL_JSON_VALUE)) //
				.havingHeader(customHeaderName, contains("bravo")); //

		verifyThatRequest().havingPath(startsWith("/actors/")) // single actor
				.havingHeader(HttpHeaders.ACCEPT, contains(MediaTypes.HAL_JSON_VALUE)) //
				.havingHeader(customHeaderName, contains("charlie")); //
	}

	private static void setUpActors() {

		EntityModel<Actor> actor = EntityModel.of(new Actor("Keanu Reaves"));
		String actorUri = server.mockResourceFor(actor);

		Movie movie = new Movie("The Matrix");
		EntityModel<Movie> resource = EntityModel.of(movie);
		resource.add(Link.of(actorUri, "actor"));

		server.mockResourceFor(resource);
		server.finishMocking();
	}

	static class CountingInterceptor implements ClientHttpRequestInterceptor {

		int intercepted;

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			this.intercepted++;
			return execution.execute(request, body);
		}
	}

	static class GitHubLinkDiscoverer extends JsonPathLinkDiscoverer {

		public GitHubLinkDiscoverer() {
			super("$.%s_url", MediaType.APPLICATION_JSON);
		}
	}
}
