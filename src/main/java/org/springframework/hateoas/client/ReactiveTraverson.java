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

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.UriTemplate;
import org.springframework.hateoas.client.Rels.Rel;
import org.springframework.hateoas.mediatype.hal.HalLinkDiscoverer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.plugin.core.PluginRegistry;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reactive version of {@link Traverson}.
 *
 * @author Rob Worsnop
 */
public class ReactiveTraverson {

    private static final TraversonDefaults DEFAULTS;

    static {

        List<TraversonDefaults> ALL_DEFAULTS = SpringFactoriesLoader.loadFactories(TraversonDefaults.class,
            Traverson.class.getClassLoader());

        Assert.isTrue(ALL_DEFAULTS.size() == 1,
            () -> String.format("Expected to find only one TraversonDefaults instance, but found: %s", //
                ALL_DEFAULTS.stream() //
                    .map(Object::getClass) //
                    .map(Class::getName) //
                    .collect(Collectors.joining(", "))));

        DEFAULTS = ALL_DEFAULTS.get(0);
    }

    private final URI baseUri;
    private final List<MediaType> mediaTypes;
    private final WebClient webClient;

    private LinkDiscoverers discoverers;

    /**
     * Creates a new {@link Traverson} interacting with the given base URI and using the given {@link MediaType}s to
     * interact with the service.
     *
     * @param baseUri must not be {@literal null}.
     * @param mediaTypes must not be {@literal null} or empty.
     */
    public ReactiveTraverson(WebClient webClient, URI baseUri, MediaType... mediaTypes) {
        this(webClient, baseUri, Arrays.asList(mediaTypes));
    }

    /**
     * Creates a new {@link Traverson} interacting with the given base URI and using the given {@link MediaType}s to
     * interact with the service.
     *
     * @param baseUri must not be {@literal null}.
     * @param mediaTypes must not be {@literal null} or empty.
     */
    public ReactiveTraverson(WebClient webClient, URI baseUri, List<MediaType> mediaTypes) {

        Assert.notNull(baseUri, "Base URI must not be null!");
        Assert.notEmpty(mediaTypes, "At least one media type must be given!");

        this.mediaTypes = mediaTypes;
        this.baseUri = baseUri;
        this.webClient = webClient;

        setLinkDiscoverers(DEFAULTS.getLinkDiscoverers(mediaTypes));
    }

    /**
     * Sets the {@link LinkDiscoverers} to use. By default a single {@link HalLinkDiscoverer} is registered. If
     * {@literal null} is provided the default is reapplied.
     *
     * @param discoverer can be {@literal null}.
     * @return
     */
    public ReactiveTraverson setLinkDiscoverers(@Nullable List<? extends LinkDiscoverer> discoverer) {

        List<? extends LinkDiscoverer> defaultedDiscoverers = discoverer == null //
            ? DEFAULTS.getLinkDiscoverers(mediaTypes) //
            : discoverer;

        this.discoverers = new LinkDiscoverers(PluginRegistry.of(defaultedDiscoverers));

        return this;
    }

    /**
     * Sets up a {@link TraversalBuilder} to follow the given rels.
     *
     * @param rels must not be {@literal null} or empty.
     * @return
     * @see TraversalBuilder
     */
    public TraversalBuilder follow(String... rels) {
        return new TraversalBuilder().follow(rels);
    }

    /**
     * Sets up a {@link TraversalBuilder} for a single rel with customized details.
     *
     * @param hop must not be {@literal null}
     * @return
     */
    public TraversalBuilder follow(Hop hop) {
        return new TraversalBuilder().follow(hop);
    }

    private HttpHeaders prepareRequest(HttpHeaders headers) {

        HttpHeaders toSend = new HttpHeaders();
        toSend.putAll(headers);

        if (headers.getAccept().isEmpty()) {
            toSend.setAccept(mediaTypes);
        }

        return toSend;
    }

    /**
     * Builder API to customize traversals.
     *
     * @author Oliver Gierke
     */
    public class TraversalBuilder {

        private static final String MEDIA_TYPE_HEADER_NOT_FOUND = "Response for request to %s did not expose a content type! Unable to identify links!";
        private static final String LINK_NOT_FOUND = "Expected to find link with rel '%s' in response %s!";

        private final List<Hop> rels = new ArrayList<>();
        private Map<String, Object> templateParameters = new HashMap<>();
        private HttpHeaders headers = new HttpHeaders();

        private TraversalBuilder() {}

        /**
         * Follows the given rels one by one, which means a request per rel to discover the next resource with the rel in
         * line.
         *
         * @param rels must not be {@literal null}.
         * @return
         */
        public TraversalBuilder follow(String... rels) {

            Assert.notNull(rels, "Rels must not be null!");

            Arrays.stream(rels) //
                .map(Hop::rel) //
                .forEach(this.rels::add);

            return this;
        }

        /**
         * Follows the given rels one by one, which means a request per rel to discover the next resource with the rel in
         * line.
         *
         * @param hop must not be {@literal null}.
         * @return
         * @see Hop#rel(String)
         */
        public TraversalBuilder follow(Hop hop) {

            Assert.notNull(hop, "Hop must not be null!");

            this.rels.add(hop);

            return this;
        }

        /**
         * Adds the given operations parameters to the traversal. If a link discovered by the traversal is templated, the
         * given parameters will be used to expand the operations into a resolvable URI.
         *
         * @param parameters can be {@literal null}.
         * @return
         */
        public TraversalBuilder withTemplateParameters(Map<String, Object> parameters) {

            Assert.notNull(parameters, "Parameters must not be null!");

            this.templateParameters = parameters;
            return this;
        }

        /**
         * The {@link HttpHeaders} that shall be used for the requests of the traversal.
         *
         * @param headers can be {@literal null}.
         * @return
         */
        public TraversalBuilder withHeaders(HttpHeaders headers) {

            Assert.notNull(headers, "Headers must not be null!");

            this.headers = headers;
            return this;
        }

        public Mono<ClientResponse> exchange() {
            return toRequestHeadersSpec().flatMap(WebClient.RequestHeadersSpec::exchange);
        }

        public Mono<WebClient.ResponseSpec> retrieve() {
            return toRequestHeadersSpec().map(WebClient.RequestHeadersSpec::retrieve);
        }

        /**
         * Returns the {@link Link} found for the last rel in the rels configured to follow. Will expand the final
         * {@link Link} using the
         *
         * @return
         * @see #withTemplateParameters(Map)
         * @since 0.15
         */
        public Mono<Link> asLink() {
            return traverseToLink(true);
        }

        /**
         * Returns the templated {@link Link} found for the last relation in the rels configured to follow.
         *
         * @return
         * @since 0.17
         */
        public Mono<Link> asTemplatedLink() {
            return traverseToLink(false);
        }

        private Mono<WebClient.RequestHeadersSpec<?>> toRequestHeadersSpec() {
            return traverseToExpandedFinalUrl()
                .map(uriAndHeaders -> webClient.get()
                    .uri(uriAndHeaders.getUri())
                    .headers(hdrs -> hdrs.addAll(prepareRequest(mergeHeaders(this.headers, uriAndHeaders.getHttpHeaders())))));
        }

        private Mono<Link> traverseToLink(boolean expandFinalUrl) {

            Assert.isTrue(rels.size() > 0, "At least one rel needs to be provided!");

            Mono<String> url = expandFinalUrl ? traverseToExpandedFinalUrl().map(URIAndHeaders::getUri).map(URI::toString) :
                traverseToFinalUrl().map(UriStringAndHeaders::getUri);

            return url.map(uri -> Link.of(uri, rels.get(rels.size() - 1).getRel()));
        }

        private Mono<UriStringAndHeaders> traverseToFinalUrl() {
            return getAndFindLinkWithRel(baseUri.toString(), rels.iterator(), HttpHeaders.EMPTY)
                .map(uriAndHeaders -> new UriStringAndHeaders(
                    UriTemplate.of(uriAndHeaders.getUri()).toString(), uriAndHeaders.getHttpHeaders()));
        }

        private Mono<URIAndHeaders> traverseToExpandedFinalUrl() {

            return getAndFindLinkWithRel(baseUri.toString(), rels.iterator(), HttpHeaders.EMPTY)
                .map(uriAndHeaders -> new URIAndHeaders(UriTemplate.of(uriAndHeaders.getUri()).expand(templateParameters),
                    uriAndHeaders.getHttpHeaders()));
        }

        private Mono<UriStringAndHeaders> getAndFindLinkWithRel(String uri, Iterator<Hop> rels, HttpHeaders extraHeaders) {

            if (!rels.hasNext()) {
                return Mono.just(new UriStringAndHeaders(uri, extraHeaders));
            }

            HttpHeaders headers = prepareRequest(mergeHeaders(this.headers, extraHeaders));
            URI target = UriTemplate.of(uri).expand();

            return webClient.get()
                .uri(target)
                .headers(hdrs -> hdrs.addAll(headers))
                .retrieve()
                .toEntity(String.class)
                .flatMap(responseEntity -> getAndFindLinkWithRel(responseEntity, target, rels));
        }

        private Mono<UriStringAndHeaders> getAndFindLinkWithRel(ResponseEntity<String> responseEntity,
                                                                URI target,
                                                                Iterator<Hop> rels) {
            MediaType contentType = responseEntity.getHeaders().getContentType();

            if (contentType == null) {
                throw new IllegalStateException(String.format(MEDIA_TYPE_HEADER_NOT_FOUND, target));
            }

            String responseBody = responseEntity.getBody();

            Hop thisHop = rels.next();
            Rel rel = Rels.getRelFor(thisHop.getRel(), discoverers);

            Link link = rel.findInResponse(responseBody == null ? "" : responseBody, contentType) //
                .orElseThrow(() -> new IllegalStateException(String.format(LINK_NOT_FOUND, rel, responseBody)));

            String linkTarget = thisHop.hasParameters() //
                ? link.expand(thisHop.getMergedParameters(templateParameters)).getHref() //
                : link.getHref();

            return getAndFindLinkWithRel(linkTarget, rels, thisHop.getHeaders());
        }

        /**
         * Combine two sets of {@link HttpHeaders} into one.
         *
         * @param headersA
         * @param headersB
         * @return
         */
        private HttpHeaders mergeHeaders(HttpHeaders headersA, HttpHeaders headersB) {

            HttpHeaders mergedHeaders = new HttpHeaders();

            mergedHeaders.addAll(headersA);
            mergedHeaders.addAll(headersB);

            return mergedHeaders;
        }
    }

    /**
     * Temporary container for a string-base {@literal URI} and {@link HttpHeaders}.
     */
    @Value
    @RequiredArgsConstructor
    private static class UriStringAndHeaders {

        private final String uri;
        private final HttpHeaders httpHeaders;
    }

    /**
     * Temporary container for a {@link URI}-based {@literal URI} and {@link HttpHeaders}.
     */
    @Value
    @RequiredArgsConstructor
    private static class URIAndHeaders {

        private final URI uri;
        private final HttpHeaders httpHeaders;
    }
}
