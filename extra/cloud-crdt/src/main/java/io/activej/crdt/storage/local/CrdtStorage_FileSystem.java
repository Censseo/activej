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

package io.activej.crdt.storage.local;

import io.activej.async.function.AsyncRunnable;
import io.activej.async.function.AsyncRunnables;
import io.activej.async.service.ReactiveService;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.ApplicationSettings;
import io.activej.common.builder.AbstractBuilder;
import io.activej.crdt.CrdtData;
import io.activej.crdt.CrdtException;
import io.activej.crdt.CrdtTombstone;
import io.activej.crdt.function.CrdtFilter;
import io.activej.crdt.function.CrdtFunction;
import io.activej.crdt.primitives.CrdtType;
import io.activej.crdt.storage.ICrdtStorage;
import io.activej.crdt.util.BinarySerializer_CrdtData;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelConsumers;
import io.activej.datastream.StreamConsumer;
import io.activej.datastream.StreamDataAcceptor;
import io.activej.datastream.StreamSupplier;
import io.activej.datastream.csp.ChannelDeserializer;
import io.activej.datastream.csp.ChannelSerializer;
import io.activej.datastream.processor.StreamFilter;
import io.activej.datastream.processor.StreamReducer;
import io.activej.datastream.processor.StreamReducers;
import io.activej.datastream.stats.StreamStats;
import io.activej.datastream.stats.StreamStats_Basic;
import io.activej.datastream.stats.StreamStats_Detailed;
import io.activej.fs.IFileSystem;
import io.activej.fs.FileMetadata;
import io.activej.fs.exception.FileNotFoundException;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.jmx.stats.EventStats;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import io.activej.serializer.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.activej.common.Utils.entriesToMap;
import static io.activej.crdt.util.BinarySerializer_CrdtData.TIMESTAMP_SERIALIZER;
import static io.activej.crdt.util.Utils.onItem;
import static io.activej.reactor.Reactive.checkInReactorThread;

@SuppressWarnings("rawtypes")
public final class CrdtStorage_FileSystem<K extends Comparable<K>, S> extends AbstractReactive
		implements ICrdtStorage<K, S>, ReactiveService, ReactiveJmxBeanWithStats {
	private static final Logger logger = LoggerFactory.getLogger(CrdtStorage_FileSystem.class);

	public static final Duration DEFAULT_SMOOTHING_WINDOW = ApplicationSettings.getDuration(CrdtStorage_FileSystem.class, "smoothingWindow", Duration.ofMinutes(1));

	public static final String FILE_EXTENSION = ".bin";

	private final IFileSystem fileSystem;
	private final CrdtFunction<S> function;
	private final BinarySerializer<CrdtReducingData<K, S>> serializer;

	private @Nullable Set<String> taken;

	private Supplier<String> namingStrategy = () -> UUID.randomUUID().toString();

	private CrdtFilter<S> filter = $ -> true;

	// region JMX
	private boolean detailedStats;

	private final AsyncRunnable consolidate = AsyncRunnables.reuse(this::doConsolidate);

	private final StreamStats_Basic<CrdtData<K, S>> uploadStats = StreamStats.basic();
	private final StreamStats_Detailed<CrdtData<K, S>> uploadStatsDetailed = StreamStats.detailed();
	private final StreamStats_Basic<CrdtData<K, S>> downloadStats = StreamStats.basic();
	private final StreamStats_Detailed<CrdtData<K, S>> downloadStatsDetailed = StreamStats.detailed();
	private final StreamStats_Basic<CrdtData<K, S>> takeStats = StreamStats.basic();
	private final StreamStats_Detailed<CrdtData<K, S>> takeStatsDetailed = StreamStats.detailed();
	private final StreamStats_Basic<CrdtTombstone<K>> removeStats = StreamStats.basic();
	private final StreamStats_Detailed<CrdtTombstone<K>> removeStatsDetailed = StreamStats.detailed();

	private final EventStats uploadedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats downloadedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats takenItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats removedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);

	private final PromiseStats consolidationStats = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	// endregion

	private CrdtStorage_FileSystem(Reactor reactor, IFileSystem fileSystem, BinarySerializer_CrdtData<K, S> serializer, CrdtFunction<S> function) {
		super(reactor);
		this.fileSystem = fileSystem;
		this.function = function;
		this.serializer = createSerializer(serializer);
	}

	public static <K extends Comparable<K>, S> CrdtStorage_FileSystem<K, S> create(
			Reactor reactor, IFileSystem fileSystem,
			BinarySerializer_CrdtData<K, S> serializer,
			CrdtFunction<S> function
	) {
		return builder(reactor, fileSystem, serializer, function).build();
	}

	public static <K extends Comparable<K>, S extends CrdtType<S>> CrdtStorage_FileSystem<K, S> create(
			Reactor reactor, IFileSystem fileSystem,
			BinarySerializer_CrdtData<K, S> serializer
	) {
		return builder(reactor, fileSystem, serializer, CrdtFunction.ofCrdtType()).build();
	}

	public static <K extends Comparable<K>, S> CrdtStorage_FileSystem<K, S>.Builder builder(
			Reactor reactor, IFileSystem fileSystem,
			BinarySerializer_CrdtData<K, S> serializer,
			CrdtFunction<S> function
	) {
		return new CrdtStorage_FileSystem<>(reactor, fileSystem, serializer, function).new Builder();
	}

	public static <K extends Comparable<K>, S extends CrdtType<S>> CrdtStorage_FileSystem<K, S>.Builder builder(
			Reactor reactor, IFileSystem fileSystem,
			BinarySerializer_CrdtData<K, S> serializer
	) {
		return new CrdtStorage_FileSystem<>(reactor, fileSystem, serializer, CrdtFunction.ofCrdtType()).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, CrdtStorage_FileSystem<K, S>> {
		private Builder() {}

		public Builder withNamingStrategy(Supplier<String> namingStrategy) {
			checkNotBuilt(this);
			CrdtStorage_FileSystem.this.namingStrategy = namingStrategy;
			return this;
		}

		public Builder withFilter(CrdtFilter<S> filter) {
			checkNotBuilt(this);
			CrdtStorage_FileSystem.this.filter = filter;
			return this;
		}

		@Override
		protected CrdtStorage_FileSystem<K, S> doBuild() {
			return CrdtStorage_FileSystem.this;
		}
	}

	@Override
	public Promise<StreamConsumer<CrdtData<K, S>>> upload() {
		checkInReactorThread(this);
		String filename = namingStrategy.get() + FILE_EXTENSION;
		return Promise.of(this.<CrdtData<K, S>>uploadNonEmpty(filename, CrdtReducingData::ofData)
				.transformWith(detailedStats ? uploadStatsDetailed : uploadStats)
				.transformWith(onItem(uploadedItems::recordEvent))
				.withAcknowledgement(ack -> ack
						.mapException(e -> new CrdtException("Error while uploading CRDT data to file", e))));
	}

	@Override
	public Promise<StreamSupplier<CrdtData<K, S>>> download(long timestamp) {
		checkInReactorThread(this);
		return Promises.retry(($, e) -> !(e instanceof FileNotFoundException),
						() -> fileSystem.list("*")
								.then(fileMap -> doDownload(fileMap.keySet(), timestamp, false))
								.map(supplier -> supplier
										.transformWith(StreamFilter.mapper(reducingData -> new CrdtData<>(reducingData.key, reducingData.timestamp, reducingData.state)))
										.transformWith(detailedStats ? downloadStatsDetailed : downloadStats)
										.transformWith(onItem(downloadedItems::recordEvent))))
				.mapException(e -> new CrdtException("Failed to download CRDT data", e));
	}

	@Override
	public Promise<StreamSupplier<CrdtData<K, S>>> take() {
		checkInReactorThread(this);
		if (taken != null) {
			return Promise.ofException(new CrdtException("Data is already being taken"));
		}
		taken = new HashSet<>();
		return Promises.retry(($, e) -> !(e instanceof FileNotFoundException),
						() -> fileSystem.list("*")
								.whenResult(fileMap -> taken.addAll(fileMap.keySet()))
								.then(fileMap -> doDownload(fileMap.keySet(), 0, false)
										.whenException(e -> taken = null)
										.map(supplier -> supplier
												.transformWith(StreamFilter.mapper(reducingData -> new CrdtData<>(reducingData.key, reducingData.timestamp, reducingData.state)))
												.transformWith(detailedStats ? takeStatsDetailed : takeStats)
												.transformWith(onItem(takenItems::recordEvent)))
										.whenResult(supplier -> supplier.getAcknowledgement()
												.then(() -> fileSystem.deleteAll(fileMap.keySet()))
												.whenComplete(() -> taken = null))))
				.mapException(e -> new CrdtException("Failed to take CRDT data", e));
	}

	private Promise<StreamSupplier<CrdtReducingData<K, S>>> doDownload(Set<String> files, long timestamp, boolean includeTombstones) {
		return Promises.toList(files.stream()
						.map(fileName -> fileSystem.download(fileName)
								.map(supplier -> supplier
										.transformWith(ChannelDeserializer.create(serializer))
										.transformWith(StreamFilter.create(data -> data.timestamp >= timestamp))
								)))
				.map(suppliers -> {
					StreamReducer<K, CrdtReducingData<K, S>, CrdtAccumulator<S>> reducer = StreamReducer.create();

					suppliers.forEach(supplier -> supplier.streamTo(reducer.newInput(x -> x.key, new CrdtReducer(includeTombstones))));

					return reducer.getOutput()
							.withEndOfStream(eos -> eos
									.mapException(e -> new CrdtException("Error while downloading CRDT data", e)));
				});
	}

	@Override
	public Promise<StreamConsumer<CrdtTombstone<K>>> remove() {
		checkInReactorThread(this);
		String filename = namingStrategy.get() + FILE_EXTENSION;
		return Promise.of(this.<CrdtTombstone<K>>uploadNonEmpty(filename, CrdtReducingData::ofTombstone)
				.transformWith(detailedStats ? removeStatsDetailed : removeStats)
				.transformWith(onItem(removedItems::recordEvent))
				.withAcknowledgement(ack -> ack
						.mapException(e -> new CrdtException("Error while removing CRDT data", e))));
	}

	@Override
	public Promise<Void> ping() {
		checkInReactorThread(this);
		return fileSystem.ping()
				.mapException(e -> new CrdtException("Failed to PING file system", e));
	}

	@Override
	public Promise<?> start() {
		checkInReactorThread(this);
		return Promise.complete();
	}

	@Override
	public Promise<?> stop() {
		checkInReactorThread(this);
		return Promise.complete();
	}

	public Promise<Void> consolidate() {
		checkInReactorThread(this);
		return consolidate.run()
				.whenComplete(consolidationStats.recordStats());
	}

	private Promise<Void> doConsolidate() {
		return fileSystem.list("*")
				.map(fileMap -> taken == null ?
						fileMap :
						entriesToMap(fileMap.entrySet().stream().filter(entry -> !taken.contains(entry.getKey()))))
				.map(CrdtStorage_FileSystem::pickFilesForConsolidation)
				.then(filesToConsolidate -> {
					if (filesToConsolidate.isEmpty()) {
						logger.info("No files to consolidate");
						return Promise.complete();
					}

					String name = namingStrategy.get() + FILE_EXTENSION;

					logger.info("Started consolidating files into {} from {}", name, filesToConsolidate);

					return doDownload(filesToConsolidate, 0, true)
							.then(crdtSupplier -> crdtSupplier.streamTo(uploadNonEmpty(name, Function.identity())))
							.then(() -> fileSystem.deleteAll(filesToConsolidate));
				})
				.mapException(e -> new CrdtException("Files consolidation failed", e));
	}

	@VisibleForTesting
	static Set<String> pickFilesForConsolidation(Map<String, FileMetadata> files) {
		if (files.isEmpty()) return Set.of();

		Map<Integer, Set<String>> groups = new TreeMap<>();
		for (Map.Entry<String, FileMetadata> entry : files.entrySet()) {
			int groupIdx = (int) Math.log10(entry.getValue().getSize());
			groups.computeIfAbsent(groupIdx, k -> new HashSet<>()).add(entry.getKey());
		}

		Set<String> groupToConsolidate = Set.of();

		for (Set<String> group : groups.values()) {
			int groupSize = group.size();
			if (groupSize > 1 && groupSize > groupToConsolidate.size()) {
				groupToConsolidate = group;
			}
		}

		return groupToConsolidate;
	}

	private <T> StreamConsumer<T> uploadNonEmpty(String filename, Function<T, CrdtReducingData<K, S>> mapping) {
		SettablePromise<ChannelConsumer<ByteBuf>> consumerPromise = new SettablePromise<>();
		NonEmptyFilter<T> nonEmptyFilter = new NonEmptyFilter<>(() -> fileSystem.upload(filename)
				.whenComplete(consumerPromise::accept));

		return StreamConsumer.ofSupplier(supplier ->
				supplier
						.transformWith(nonEmptyFilter)
						.transformWith(StreamFilter.mapper(mapping))
						.transformWith(ChannelSerializer.create(serializer))
						.withEndOfStream(eos -> eos
								.whenComplete(() -> {
									if (nonEmptyFilter.isEmpty()) {
										consumerPromise.set(ChannelConsumers.recycling());
									}
								}))
						.streamTo(consumerPromise));
	}

	private static <K extends Comparable<K>, S> BinarySerializer<CrdtReducingData<K, S>> createSerializer(BinarySerializer_CrdtData<K, S> serializer) {
		BinarySerializer<K> keySerializer = serializer.getKeySerializer();
		BinarySerializer<@Nullable S> stateSerializer = BinarySerializers.ofNullable(serializer.getStateSerializer());
		return new BinarySerializer<>() {
			@Override
			public void encode(BinaryOutput out, CrdtReducingData<K, S> item) {
				keySerializer.encode(out, item.key);
				stateSerializer.encode(out, item.state);
				TIMESTAMP_SERIALIZER.encode(out, item.timestamp);
			}

			@Override
			public CrdtReducingData<K, S> decode(BinaryInput in) throws CorruptedDataException {
				return new CrdtReducingData<>(
						keySerializer.decode(in),
						stateSerializer.decode(in),
						TIMESTAMP_SERIALIZER.decode(in)
				);
			}
		};
	}

	record CrdtReducingData<K extends Comparable<K>, S>(K key, @Nullable S state, long timestamp) {
		static <K extends Comparable<K>, S> CrdtReducingData<K, S> ofData(CrdtData<K, S> data) {
			return new CrdtReducingData<>(data.getKey(), data.getState(), data.getTimestamp());
		}

		static <K extends Comparable<K>, S> CrdtReducingData<K, S> ofTombstone(CrdtTombstone<K> tombstone) {
			return new CrdtReducingData<>(tombstone.getKey(), null, tombstone.getTimestamp());
		}
	}

	static class CrdtAccumulator<S> {
		final Set<CrdtEntry<S>> entries = new HashSet<>();
		private long tombstoneTimestamp;

		CrdtAccumulator(@Nullable S state, long timestamp) {
			if (state == null) {
				tombstoneTimestamp = timestamp;
			} else {
				entries.add(new CrdtEntry<>(state, timestamp));
			}
		}
	}

	record CrdtEntry<S>(S state, long timestamp) {}

	class CrdtReducer implements StreamReducers.Reducer<K, CrdtReducingData<K, S>, CrdtReducingData<K, S>, CrdtAccumulator<S>> {
		final boolean includeTombstones;

		CrdtReducer(boolean includeTombstones) {
			this.includeTombstones = includeTombstones;
		}

		@Override
		public CrdtAccumulator<S> onFirstItem(StreamDataAcceptor<CrdtReducingData<K, S>> stream, K key, CrdtReducingData<K, S> firstValue) {
			return new CrdtAccumulator<>(firstValue.state, firstValue.timestamp);
		}

		@Override
		public CrdtAccumulator<S> onNextItem(StreamDataAcceptor<CrdtReducingData<K, S>> stream, K key, CrdtReducingData<K, S> nextValue, CrdtAccumulator<S> accumulator) {
			if (nextValue.state != null) {
				if (nextValue.timestamp > accumulator.tombstoneTimestamp) {
					accumulator.entries.add(new CrdtEntry<>(nextValue.state, nextValue.timestamp));
				}
			} else {
				accumulator.tombstoneTimestamp = nextValue.timestamp;
				accumulator.entries.removeIf(entry -> entry.timestamp <= nextValue.timestamp);
			}
			return accumulator;
		}

		@Override
		public void onComplete(StreamDataAcceptor<CrdtReducingData<K, S>> stream, K key, CrdtAccumulator<S> accumulator) {
			if (accumulator.entries.isEmpty()) {
				if (includeTombstones) {
					stream.accept(new CrdtReducingData<>(key, null, accumulator.tombstoneTimestamp));
				}
				return;
			}

			Iterator<CrdtEntry<S>> iterator = accumulator.entries.iterator();
			CrdtEntry<S> firstEntry = iterator.next();
			long timestamp = firstEntry.timestamp;
			S state = firstEntry.state;

			while (iterator.hasNext()) {
				CrdtEntry<S> nextEntry = iterator.next();
				state = function.merge(state, timestamp, nextEntry.state, nextEntry.timestamp);
				timestamp = Long.max(timestamp, nextEntry.timestamp);
			}

			if (filter.test(state)) {
				stream.accept(new CrdtReducingData<>(key, state, timestamp));
			}
		}
	}

	private static final class NonEmptyFilter<T> extends StreamFilter<T, T> {
		private final Runnable onNonEmpty;
		private boolean empty = true;

		private NonEmptyFilter(Runnable onNonEmpty) {
			this.onNonEmpty = onNonEmpty;
		}

		@Override
		protected StreamDataAcceptor<T> onResumed(StreamDataAcceptor<T> output) {
			return item -> {
				if (empty) {
					empty = false;
					onNonEmpty.run();
				}
				output.accept(item);
			};
		}

		public boolean isEmpty() {
			return empty;
		}
	}

	// region JMX
	@JmxOperation
	public void startDetailedMonitoring() {
		detailedStats = true;
	}

	@JmxOperation
	public void stopDetailedMonitoring() {
		detailedStats = false;
	}

	@JmxAttribute
	public StreamStats_Basic getUploadStats() {
		return uploadStats;
	}

	@JmxAttribute
	public StreamStats_Detailed getUploadStatsDetailed() {
		return uploadStatsDetailed;
	}

	@JmxAttribute
	public StreamStats_Basic getDownloadStats() {
		return downloadStats;
	}

	@JmxAttribute
	public StreamStats_Detailed getDownloadStatsDetailed() {
		return downloadStatsDetailed;
	}

	@JmxAttribute
	public StreamStats_Basic getTakeStats() {
		return takeStats;
	}

	@JmxAttribute
	public StreamStats_Detailed getTakeStatsDetailed() {
		return takeStatsDetailed;
	}

	@JmxAttribute
	public StreamStats_Basic getRemoveStats() {
		return removeStats;
	}

	@JmxAttribute
	public StreamStats_Detailed getRemoveStatsDetailed() {
		return removeStatsDetailed;
	}

	@JmxAttribute
	public PromiseStats getConsolidationStats() {
		return consolidationStats;
	}

	@JmxAttribute
	public EventStats getUploadedItems() {
		return uploadedItems;
	}

	@JmxAttribute
	public EventStats getDownloadedItems() {
		return downloadedItems;
	}

	@JmxAttribute
	public EventStats getTakenItems() {
		return takenItems;
	}

	@JmxAttribute
	public EventStats getRemovedItems() {
		return removedItems;
	}
	// endregion
}