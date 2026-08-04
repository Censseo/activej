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

package io.activej.http3.testutil;

import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResourceRecord;
import io.activej.dns.protocol.DnsResponse;
import io.activej.dns.protocol.DnsTransaction;
import io.activej.promise.Promise;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link IDnsClient} that answers every query from a table the test writes, with no socket and no
 * real resolver.
 * <p>
 * {@code Http3Client} keys its connection pool on the <b>authority</b> and resolves the host only when
 * it has to open a connection, so a fixture that maps several names onto one loopback address is what
 * makes "two authorities, two connections, one server" a one-server test. Every name defaults to the
 * loopback address of {@link Http3WirePair#SERVER_ADDRESS}; {@link #fail(String)} marks a name
 * unresolvable.
 * <p>
 * Deliberately not {@code CachedDnsClient} over a stub transport: what is under test is the client's
 * pooling, not {@code core-http}'s resolver.
 */
public final class StubDnsClient implements IDnsClient {
	private final List<String> resolved = new ArrayList<>();
	private final List<String> failing = new ArrayList<>();

	/** Marks {@code domainName} unresolvable — the promise fails rather than answering. */
	public StubDnsClient fail(String domainName) {
		failing.add(domainName);
		return this;
	}

	/** Every name this client has been asked for, in order, so a test can assert nothing was resolved. */
	public List<String> resolved() {
		return resolved;
	}

	@Override
	public Promise<DnsResponse> resolve(DnsQuery query) {
		String domainName = query.getDomainName();
		resolved.add(domainName);
		if (failing.contains(domainName)) {
			return Promise.ofException(new UnknownHostException(domainName));
		}
		// The fixture's own server address, not InetAddress.getLoopbackAddress(): the stub network routes
		// on InetSocketAddress equality, and a JVM preferring IPv6 would hand back ::1 and route nowhere.
		InetAddress[] ips = {Http3WirePair.SERVER_ADDRESS.getAddress()};
		return Promise.of(DnsResponse.of(DnsTransaction.of((short) 0, query), DnsResourceRecord.of(ips, 60)));
	}

	@Override
	public void close() {
		// Nothing to close: this client owns no socket.
	}
}
