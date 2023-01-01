package io.activej.reactor;

import io.activej.reactor.nio.NioReactor;

public interface NioReactive extends Reactive {
	NioReactor getReactor();
}
