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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException;
import org.yaml.snakeyaml.representer.Representer;
import space.arim.dazzleconf.ConfigurationFactory;
import space.arim.dazzleconf.ConfigurationOptions;
import space.arim.dazzleconf.error.ConfigFormatSyntaxException;
import space.arim.dazzleconf.error.InvalidConfigException;
import space.arim.dazzleconf.ext.snakeyaml.CommentMode;
import space.arim.dazzleconf.ext.snakeyaml.SnakeYamlConfigurationFactory;
import space.arim.dazzleconf.ext.snakeyaml.SnakeYamlOptions;
import space.arim.omnibus.util.ThisClass;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ConfigHolder<C> {

	private final Class<C> configClass;
	private final ConfigurationOptions options;
	
	private volatile C instance;

	private static final SnakeYamlOptions YAML_OPTIONS;
	private static final Logger logger = LoggerFactory.getLogger(ThisClass.get());

	static {
		YAML_OPTIONS = new SnakeYamlOptions.Builder()
				.commentMode(CommentMode.alternativeWriter())
				// Substitute ${VAR} into scalars tagged !ENV, so that database passwords can come
				// from the environment instead of living in the configuration files. See
				// EnvironmentScalarConstructor for why SnakeYAML's own version is unusable here.
				.yamlSupplier(yamlSupplier())
				.build();
	}

	/**
	 * Mirrors DazzleConf's own default supplier - its package-private {@code DefaultYaml} - differing
	 * only in the constructor handed to SnakeYAML. Reproducing it faithfully matters: dropping the
	 * {@link DumperOptions} would lose {@code BLOCK} flow style and reflow every configuration file
	 * that gets written, so the three-argument {@code Yaml} constructor is the one to use rather
	 * than {@code Yaml(BaseConstructor, Representer)}.
	 * <p>
	 * {@code DumperOptions.setProcessComments} is called reflectively for the same reason DazzleConf
	 * probes for it: it does not exist in SnakeYAML 1.26, which is the version this module compiles
	 * against, but it does exist in 1.27 and later. Which one is actually on the class path is the
	 * platform's business, not ours - Velocity 4.1.1 supplies 2.5 - so the call has to be optional.
	 */
	private static Supplier<Yaml> yamlSupplier() {
		Method setProcessComments;
		try {
			setProcessComments = DumperOptions.class.getMethod("setProcessComments", boolean.class);
		} catch (NoSuchMethodException | SecurityException ex) {
			setProcessComments = null;
		}
		Method processComments = setProcessComments;
		return () -> {
			DumperOptions dumperOptions = new DumperOptions();
			if (processComments != null) {
				try {
					processComments.invoke(dumperOptions, true);
				} catch (IllegalAccessException | InvocationTargetException ex) {
					throw new IllegalStateException("Cannot enable comment processing", ex);
				}
			}
			dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
			// Representer(DumperOptions), not the no-argument Representer(): the latter is gone in
			// SnakeYAML 2.x, and the copy on the class path at runtime belongs to the platform.
			return new Yaml(new EnvironmentScalarConstructor(), new Representer(dumperOptions), dumperOptions);
		};
	}

	public ConfigHolder(Class<C> configClass, ConfigurationOptions options) {
		this.configClass = Objects.requireNonNull(configClass, "configClass");
		this.options = Objects.requireNonNull(options, "options");
	}

	public Class<C> getConfigClass() {
		return configClass;
	}

	public C getConfigData() {
		return instance;
	}

	public CompletableFuture<ConfigResult> reload(Path path) {
		return CompletableFuture.supplyAsync(() -> {
			ConfigurationFactory<C> factory = SnakeYamlConfigurationFactory.create(configClass, options, YAML_OPTIONS);
			C defaults = factory.loadDefaults();
			try {
				if (!Files.exists(path)) {
					try (FileChannel fileChannel = FileChannel.open(path,
							StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {

						factory.write(defaults, fileChannel);
					}
					this.instance = defaults;
					return ConfigResult.SUCCESS_WITH_DEFAULTS;
				}
				C instance = loadFromPath(factory, defaults, path);
				this.instance = instance;
				if (instance == defaults) {
					// loadFromPath indicates a user failure
					return ConfigResult.USER_ERROR;
				}
				return ConfigResult.SUCCESS_LOADED;

			} catch (IOException ex) {
				logger.warn("Encountered I/O error while reloading the configuration", ex);
				return ConfigResult.IO_ERROR;
			}
		});
	}

	/**
	 * Attempts to load the configuration from the specified path
	 *
	 * @param defaults the default configuration
	 * @param path the path
	 * @return the loaded configuration if everything went properly, or the default configuration if invalid
	 */
	private C loadFromPath(ConfigurationFactory<C> factory,
						   C defaults,
						   Path path) throws IOException {
		C config;
		// Load existing configuration
		try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ)) {

			config = factory.load(fileChannel, defaults);

		} catch (ConfigFormatSyntaxException ex) {
			// A placeholder naming a variable that is not set must not degrade into "warn and carry
			// on with the defaults". Falling back would start the plugin against the wrong database
			// rather than against none, and reloadConfigs() treats USER_ERROR as success, so nothing
			// downstream would stop the boot. Rethrow so that startup fails with the real reason.
			if (ex.getCause() instanceof MissingEnvironmentVariableException) {
				throw (MissingEnvironmentVariableException) ex.getCause();
			}
			logger.warn(
					"The YAML syntax in your configuration is invalid. "
					+ "Please use a YAML validator such as https://yaml-online-parser.appspot.com/. "
					+ "Paste your configuration there and use it to work through errors. "
					+ "Run /libertybans reload when done. For now, the default configuration will be used.", ex);
			return defaults;

		} catch (InvalidConfigException ex) {
			logger.warn(
					"The values in your configuration are invalid. "
					+ "Please correct the issue and run /libertybans reload. "
					+ "For now, the default configuration will be used.", ex);
			return defaults;
		}
		// Upstream rewrites the file here when the loaded configuration is missing keys
		// (config instanceof AuxiliaryKeys), to merge in the new defaults. That branch is removed
		// deliberately, for two reasons.
		//
		// It would defeat the !ENV substitution above: what the plugin holds after loading is the
		// resolved value, so the rewrite would replace `password: !ENV ${DB_PASSWORD}` with the
		// database password in plain text, in a file that is otherwise safe to read.
		//
		// And it would achieve nothing anyway. Our configuration files arrive inside the image and
		// are laid out afresh on every start; plugins/ is not a volume, so anything the plugin
		// writes lands in the container's writable layer and is gone when the container is
		// recreated. Adding new keys is something we do together with the plugin update, in the
		// image, not at runtime.
		//
		// The "file does not exist -> write the defaults" path in reload() stays: it is what creates
		// the configuration on a clean machine, and without it the plugin does not come up at all.
		return config;
	}
	
}
