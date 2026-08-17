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

package io.activej.jmx;


import io.activej.jmx.api.JmxBean;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.stats.EventStats;
import io.activej.jmx.stats.ValueStats;
import org.junit.Test;

import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression guard: a bean whose attributes expose the real {@code boot-jmx-stats} accumulators must be
 * registerable. {@link EventStats}/{@link ValueStats} carry an optional {@code smoothingWindow} attribute of
 * type {@link Duration} — neither settable nor walkable as a JMX attribute — which used to abort registration
 * of the whole bean ("Setters are allowed only on attributes of simple, custom or Enum types" /
 * "Unrecognized type of Jmx attribute: java.time.Duration"). Every {@code HttpServer}/{@code RpcServer}
 * JMX surface is built from these accumulators, so a regression here disables JMX platform-wide.
 */
public class RealStatsRegistrationTest {
	private static final Map<String, AttributeModifier<?>> NO_MODIFIERS = Map.of();
	private static final Map<Class<?>, DynamicMBeanFactory.JmxCustomTypeAdapter<?>> NO_CUSTOM_TYPES = Map.of();

	@JmxBean(io.activej.jmx.helper.JmxBeanAdapterStub.class)
	public static final class StatsBean {
		private final EventStats direct = EventStats.create(Duration.ofMinutes(1));
		private final ValueStats latency = ValueStats.builder(Duration.ofMinutes(1)).withUnit("milliseconds").build();
		private final Map<String, EventStats> perKey = Map.of(
			"a", EventStats.create(Duration.ofMinutes(1)),
			"b", EventStats.create(Duration.ofMinutes(1)));

		public void record(long millis) {
			direct.recordEvent();
			latency.recordValue(millis);
			perKey.get("a").recordEvent();
		}

		@JmxAttribute(extraSubAttributes = "totalCount")
		public EventStats getDirect() {
			return direct;
		}

		@JmxAttribute
		public ValueStats getLatency() {
			return latency;
		}

		@JmxAttribute
		public Map<String, EventStats> getPerKey() {
			return perKey;
		}
	}

	@Test
	public void beanWithRealStatsRegistersAndReads() throws Exception {
		StatsBean bean = new StatsBean();
		bean.record(42);

		DynamicMBean mbean = DynamicMBeanFactory.create()
			.createDynamicMBean(List.of(bean), JmxBeanSettings.create(), true);

		MBeanInfo info = mbean.getMBeanInfo();
		Map<String, MBeanAttributeInfo> attrs = io.activej.jmx.helper.Utils.nameToAttribute(info.getAttributes());
		assertTrue(attrs.containsKey("direct"));
		assertTrue(attrs.containsKey("latency"));
		assertTrue(attrs.containsKey("perKey"));

		// the requested sub-attribute is readable through the flattened composite
		Object direct = mbean.getAttribute("direct_totalCount");
		assertEquals(1L, direct);

		// a Map-valued attribute of EventStats survives as TabularData
		Object perKey = mbean.getAttribute("perKey");
		assertTrue(perKey instanceof TabularData);
		TabularData perKeyData = (TabularData) perKey;
		assertNotNull(perKeyData.get(new Object[]{"a"}));
		assertNotNull(perKeyData.get(new Object[]{"b"}));

		// the optional Duration-typed attribute is neither settable nor rendered
		assertTrue(!attrs.containsKey("smoothingWindow"));
	}
}
