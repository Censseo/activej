/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.http;

import io.activej.async.function.AsyncSupplier;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.builder.AbstractBuilder;
import io.activej.http.loader.IStaticLoader;
import io.activej.http.loader.ResourceIsADirectoryException;
import io.activej.http.loader.ResourceNotFoundException;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.activej.http.HttpHeaderValue.ofContentType;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * This servlet allows return HTTP responses by HTTP paths from some predefined storage, mainly the filesystem.
 */
public final class Servlet_Static extends AbstractReactive
		implements AsyncServlet {
	public static final Charset DEFAULT_TXT_ENCODING = StandardCharsets.UTF_8;

	private final IStaticLoader resourceLoader;
	private Function<String, ContentType> contentTypeResolver = Servlet_Static::getContentType;
	private Function<HttpRequest, @Nullable String> pathMapper = HttpRequest::getRelativePath;
	private Supplier<HttpResponse> responseSupplier = HttpResponse::ok200;
	private final Set<String> indexResources = new LinkedHashSet<>();

	private @Nullable String defaultResource;

	private Servlet_Static(Reactor reactor, IStaticLoader resourceLoader) {
		super(reactor);
		this.resourceLoader = resourceLoader;
	}

	public static Servlet_Static create(Reactor reactor, IStaticLoader resourceLoader) {
		return builder(reactor, resourceLoader).build();
	}

	public static Servlet_Static create(Reactor reactor, IStaticLoader resourceLoader, String page) {
		return builder(reactor, resourceLoader).withMappingTo(page).build();
	}

	public static Servlet_Static ofClassPath(Reactor reactor, Executor executor, String path) {
		return builder(reactor, IStaticLoader.ofClassPath(reactor, executor, path)).build();
	}

	public static Servlet_Static ofPath(Reactor reactor, Executor executor, Path path) {
		return builder(reactor, IStaticLoader.ofPath(reactor, executor, path)).build();
	}

	public static Builder builder(Reactor reactor, IStaticLoader resourceLoader) {
		return new Servlet_Static(reactor, resourceLoader).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, Servlet_Static>{
		private Builder() {}

		@SuppressWarnings("UnusedReturnValue")
		public Builder withContentType(ContentType contentType) {
			checkNotBuilt(this);
			return withContentTypeResolver($ -> contentType);
		}

		public Builder withContentTypeResolver(Function<String, ContentType> contentTypeResolver) {
			checkNotBuilt(this);
			Servlet_Static.this.contentTypeResolver = contentTypeResolver;
			return this;
		}

		public Builder withMapping(Function<HttpRequest, String> fn) {
			checkNotBuilt(this);
			pathMapper = fn;
			return this;
		}

		public Builder withMappingTo(String path) {
			checkNotBuilt(this);
			//noinspection RedundantCast - it does not compile without the cast
			if (Servlet_Static.this.contentTypeResolver == (Function<String, ContentType>) Servlet_Static::getContentType) {
				withContentType(getContentType(path));
			}
			return withMapping($ -> path);
		}

		public Builder withMappingNotFoundTo(String defaultResource) {
			checkNotBuilt(this);
			Servlet_Static.this.defaultResource = defaultResource;
			return this;
		}

		public Builder withIndexResources(String... indexResources) {
			checkNotBuilt(this);
			Servlet_Static.this.indexResources.addAll(List.of(indexResources));
			return this;
		}

		public Builder withIndexHtml() {
			checkNotBuilt(this);
			Servlet_Static.this.indexResources.add("index.html");
			return this;
		}

		public Builder withResponse(Supplier<HttpResponse> responseSupplier) {
			checkNotBuilt(this);
			Servlet_Static.this.responseSupplier = responseSupplier;
			return this;
		}

		@Override
		protected Servlet_Static doBuild() {
			return Servlet_Static.this;
		}
	}

	public static ContentType getContentType(String path) {
		int pos = path.lastIndexOf('.');
		if (pos == -1) {
			return ContentType.of(MediaTypes.OCTET_STREAM);
		}

		String ext = path.substring(pos + 1);

		MediaType mime = MediaTypes.getByExtension(ext);
		if (mime == null) {
			mime = MediaTypes.OCTET_STREAM;
		}

		ContentType type;
		if (mime.isTextType()) {
			type = ContentType.of(mime, DEFAULT_TXT_ENCODING);
		} else {
			type = ContentType.of(mime);
		}

		return type;
	}

	private HttpResponse createHttpResponse(ByteBuf buf, ContentType contentType) {
		return responseSupplier.get()
				.withBody(buf)
				.withHeader(CONTENT_TYPE, ofContentType(contentType));
	}

	@Override
	public Promise<HttpResponse> serve(HttpRequest request) {
		checkInReactorThread(this);
		String mappedPath = pathMapper.apply(request);
		if (mappedPath == null) return Promise.ofException(HttpError.notFound404());
		ContentType contentType = contentTypeResolver.apply(mappedPath);
		return Promise.complete()
				.then(() -> (mappedPath.endsWith("/") || mappedPath.isEmpty()) ?
						tryLoadIndexResource(mappedPath) :
						resourceLoader.load(mappedPath)
								.map(byteBuf -> createHttpResponse(byteBuf, contentType))
								.then((value, e) -> {
									if (e instanceof ResourceIsADirectoryException) {
										return tryLoadIndexResource(mappedPath);
									} else {
										return Promise.of(value, e);
									}
								}))
				.then(Promise::of,
						e -> e instanceof ResourceNotFoundException ?
								tryLoadDefaultResource() :
								Promise.ofException(HttpError.ofCode(400, e)));
	}

	private Promise<HttpResponse> tryLoadIndexResource(String mappedPath) {
		String dirPath = mappedPath.endsWith("/") || mappedPath.isEmpty() ? mappedPath : (mappedPath + '/');
		return Promises.first(
						indexResources.stream()
								.map(indexResource -> (AsyncSupplier<HttpResponse>) () ->
										resourceLoader.load(dirPath + indexResource)
												.map(byteBuf -> createHttpResponse(byteBuf, contentTypeResolver.apply(indexResource)))))
				.mapException(e -> new ResourceNotFoundException("Could not find '" + mappedPath + '\'', e));
	}

	private Promise<? extends HttpResponse> tryLoadDefaultResource() {
		return defaultResource != null ?
				resourceLoader.load(defaultResource)
						.map(buf -> createHttpResponse(buf, contentTypeResolver.apply(defaultResource))) :
				Promise.ofException(HttpError.notFound404());
	}
}