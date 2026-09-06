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

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SnakeYAML {@link Constructor} which substitutes environment variables into scalars tagged
 * {@code !ENV}, so that secrets such as database passwords need not be committed to the
 * configuration files themselves.
 * <p>
 * <b>Why this exists instead of {@code org.yaml.snakeyaml.env.EnvScalarConstructor}.</b>
 * SnakeYAML ships exactly this feature, but this module compiles against SnakeYAML 1.26 - the version
 * pinned in the parent pom - and there {@code EnvScalarConstructor} is package-private; it only became
 * public in 1.27. It therefore cannot be named from here at all, and raising the pin is not a small
 * change: the Fabric module verifies every resolved artifact against gradle/verification-metadata.xml.
 * Everything this class does touch ({@code Constructor}, {@code AbstractConstruct},
 * {@code MissingEnvironmentVariableException}) is public in 1.26.
 * <p>
 * <b>Which SnakeYAML actually runs.</b> Not necessarily the one compiled against. LibertyBans declares
 * SnakeYAML as platform-provided on Velocity, Spigot and BungeeCord, and its class loader delegates
 * parent-first, so the platform's copy wins; only where the platform has none does the bundled 1.26
 * apply. Velocity 4.1.1 carries 2.5 - note that the {@code META-INF/maven/org.yaml/snakeyaml}
 * metadata inside the proxy jar still says 1.26, which is stale and should not be believed. So this
 * class must keep to the API common to 1.26 and 2.x: see the constructor, and the {@code Representer}
 * built in {@link ConfigHolder}.
 * <p>
 * <b>Deliberate difference from SnakeYAML's semantics.</b> Where SnakeYAML resolves an unset
 * {@code ${VAR}} to the empty string, this class raises an error naming the variable. An empty
 * string is the worst possible outcome for the case this feature exists to serve: an empty database
 * password does not fail here, it fails much later as a refused connection in the middle of startup,
 * and the operator then hunts for the cause in the database's log rather than in ours. The
 * substitution this replaces - a shell step in the proxy image's entrypoint - aborted the boot on an
 * unset variable, and that property must survive the move into the plugin. Write
 * {@code ${VAR:-default}} when a variable really is optional.
 * <p>
 * A variable that is <i>set but empty</i> is passed through as the empty string, which is what the
 * entrypoint script did too: it tested whether the name was present in the environment, not whether
 * its value was non-empty.
 * <p>
 * <b>Limitations, inherited from SnakeYAML's format.</b> The whole scalar must be the placeholder;
 * there is no substitution within a larger string, so {@code jdbc:mysql://${HOST}/db} is not
 * a template but a literal. Default values may not contain whitespace.
 *
 * <pre>
 * password: !ENV ${DB_PASSWORD}                # required; boot fails if unset
 * host: !ENV ${DB_HOST:-localhost}             # optional, with a default
 * user: !ENV ${DB_USER:?set the database user} # required, with a custom message
 * </pre>
 */
public final class EnvironmentScalarConstructor extends Constructor {

	/**
	 * The tag marking a scalar for environment substitution
	 */
	public static final Tag ENV_TAG = new Tag("!ENV");

	/**
	 * Matches a whole placeholder. Copied from SnakeYAML's {@code EnvScalarConstructor.ENV_FORMAT}
	 * so that documents remain portable between this implementation and the upstream one: the name
	 * is a word, the separator is one of {@code -}, {@code :-}, {@code ?}, {@code :?}, and the
	 * default value or message is any run of non-space characters.
	 */
	public static final Pattern ENV_FORMAT = Pattern
			.compile("^\\$\\{\\s*((?<name>\\w+)((?<separator>:?(-|\\?))(?<value>\\S+)?)?)\\s*\\}$");

	private final UnaryOperator<String> environment;

	public EnvironmentScalarConstructor() {
		this(System::getenv);
	}

	/**
	 * Creates an instance reading from the given environment. Exists for testing; production code
	 * wants the no-argument constructor.
	 *
	 * @param environment maps a variable name to its value, or to {@code null} when it is unset
	 */
	public EnvironmentScalarConstructor(UnaryOperator<String> environment) {
		// Constructor(LoaderOptions) rather than the no-argument Constructor(): the latter exists in
		// SnakeYAML 1.26, which this module compiles against, but was removed in 2.0, and the copy
		// actually on the class path at runtime is the platform's. Velocity 4.1.1 carries 2.5.
		// This overload exists in both, so it is the one that survives either way.
		super(new LoaderOptions());
		this.environment = environment;
		yamlConstructors.put(ENV_TAG, new ConstructEnv());
	}

	private final class ConstructEnv extends AbstractConstruct {

		@Override
		public Object construct(Node node) {
			String placeholder = constructScalar((ScalarNode) node);
			Matcher matcher = ENV_FORMAT.matcher(placeholder);
			if (!matcher.matches()) {
				// SnakeYAML calls matches() without checking it, then reads the groups, which
				// yields an inscrutable IllegalStateException. Say what is actually wrong.
				throw new MissingEnvironmentVariableException(
						"Malformed !ENV placeholder: '" + placeholder + "'. "
								+ "Expected the entire value to be ${VAR}, ${VAR:-default} or ${VAR:?message}.");
			}
			String name = matcher.group("name");
			String separator = matcher.group("separator");
			String value = matcher.group("value");
			return substitute(name, separator, (value == null) ? "" : value, environment.apply(name));
		}
	}

	/**
	 * Decides the replacement for one placeholder.
	 *
	 * @param name the variable name
	 * @param separator the separator used, or {@code null} when the placeholder was a bare {@code ${VAR}}
	 * @param value the default value or the error message, never {@code null}
	 * @param environment the value found in the environment, or {@code null} when unset
	 * @return the text to substitute
	 */
	String substitute(String name, String separator, String value, String environment) {
		if (environment != null && !environment.isEmpty()) {
			return environment;
		}
		// The variable is unset, or set to the empty string
		if (separator == null) {
			// A bare ${VAR}. SnakeYAML substitutes "" here; we refuse instead. See the class javadoc.
			if (environment == null) {
				throw new MissingEnvironmentVariableException(
						"Environment variable " + name + " is not set, and the configuration gives it "
								+ "no default. Either set " + name + " or write ${" + name + ":-somedefault}.");
			}
			// Set but empty: allowed, matching the entrypoint script this replaces
			return "";
		}
		if (separator.equals("?") && environment == null) {
			throw new MissingEnvironmentVariableException("Missing mandatory variable " + name + ": " + value);
		}
		if (separator.equals(":?")) {
			if (environment == null) {
				throw new MissingEnvironmentVariableException("Missing mandatory variable " + name + ": " + value);
			}
			throw new MissingEnvironmentVariableException("Empty mandatory variable " + name + ": " + value);
		}
		if (separator.startsWith(":")) {
			// ${VAR:-default} - unset or empty both take the default
			return value;
		}
		// ${VAR-default} - only an unset variable takes the default
		return (environment == null) ? value : "";
	}

}
