package io.activej.http;

import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http.HttpHeaders.ALLOW;
import static org.junit.Assert.assertThrows;

public final class TestClientMultilineHeaders {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void testMultilineHeaders() {
		// header values containing CR or LF (obs-fold, response splitting) must be rejected
		assertThrows(IllegalArgumentException.class, () -> HttpResponse.ok200()
			.withHeader(ALLOW, "GET,\r\n HEAD"));
	}
}
