/*
 * LibertyBans
 * Copyright © 2026 Anand Beh
 *
 * LibertyBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * LibertyBans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with LibertyBans. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU Affero General Public License.
 */

package space.arim.libertybans.core.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException;
import org.yaml.snakeyaml.representer.Representer;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnvironmentScalarConstructorTest {

	private static final Map<String, String> ENVIRONMENT = Map.of(
			"SET_VALUE", "secret",
			"EMPTY_VALUE", ""
	);

	private Object load(String document) {
		DumperOptions dumperOptions = new DumperOptions();
		dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		Yaml yaml = new Yaml(
				new EnvironmentScalarConstructor(ENVIRONMENT::get), new Representer(dumperOptions), dumperOptions
		);
		return yaml.load(new StringReader(document));
	}

	private Object loadValue(String scalar) {
		return ((Map<?, ?>) load("key: " + scalar + "\n")).get("key");
	}

	@Test
	public void substitutesSetVariable() {
		assertEquals("secret", loadValue("!ENV ${SET_VALUE}"));
	}

	@Test
	public void leavesUntaggedScalarsAlone() {
		assertEquals("${SET_VALUE}", loadValue("\"${SET_VALUE}\""));
	}

	@Test
	public void usesDefaultWhenUnset() {
		assertEquals("localhost", loadValue("!ENV ${MISSING_VALUE:-localhost}"));
		assertEquals("localhost", loadValue("!ENV ${MISSING_VALUE-localhost}"));
	}

	@Test
	public void prefersEnvironmentOverDefault() {
		assertEquals("secret", loadValue("!ENV ${SET_VALUE:-localhost}"));
	}

	@Test
	public void colonDefaultAppliesToEmptyVariable() {
		assertEquals("localhost", loadValue("!ENV ${EMPTY_VALUE:-localhost}"));
	}

	@Test
	public void bareDefaultDoesNotApplyToEmptyVariable() {
		assertEquals("", loadValue("!ENV ${EMPTY_VALUE-localhost}"));
	}

	/**
	 * The deliberate departure from SnakeYAML, which yields the empty string here.
	 */
	@Test
	public void unsetVariableWithoutDefaultIsAnError() {
		var ex = assertThrows(MissingEnvironmentVariableException.class,
				() -> loadValue("!ENV ${MISSING_VALUE}"));
		assertTrue(ex.getMessage().contains("MISSING_VALUE"), ex.getMessage());
	}

	@Test
	public void emptyVariableWithoutDefaultIsPermitted() {
		assertEquals("", loadValue("!ENV ${EMPTY_VALUE}"));
	}

	@Test
	public void mandatoryVariableReportsItsMessage() {
		var ex = assertThrows(MissingEnvironmentVariableException.class,
				() -> loadValue("!ENV ${MISSING_VALUE:?set-the-password}"));
		assertTrue(ex.getMessage().contains("set-the-password"), ex.getMessage());
		assertThrows(MissingEnvironmentVariableException.class,
				() -> loadValue("!ENV ${EMPTY_VALUE:?set-the-password}"));
		// Without the colon, only an unset variable is rejected
		assertEquals("", loadValue("!ENV ${EMPTY_VALUE?set-the-password}"));
	}

	@Test
	public void malformedPlaceholderIsReported() {
		var ex = assertThrows(MissingEnvironmentVariableException.class,
				() -> loadValue("!ENV \"jdbc:mysql://${SET_VALUE}/db\""));
		assertTrue(ex.getMessage().contains("Malformed"), ex.getMessage());
	}

}
