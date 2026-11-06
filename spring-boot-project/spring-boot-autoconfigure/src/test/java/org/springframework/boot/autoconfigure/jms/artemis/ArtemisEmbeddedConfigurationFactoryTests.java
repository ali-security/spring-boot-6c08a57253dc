/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.autoconfigure.jms.artemis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.system.ApplicationTemp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for {@link ArtemisEmbeddedConfigurationFactory}
 *
 * @author Eddú Meléndez
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ArtemisEmbeddedConfigurationFactoryTests {

	@Test
	void defaultDataDir() {
		ArtemisProperties properties = new ArtemisProperties();
		properties.getEmbedded().setPersistent(true);
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		assertThat(configuration.getJournalDirectory()).startsWith(System.getProperty("java.io.tmpdir"))
				.endsWith("/journal");
	}

	@Test
	void defaultDataDirIsApplicationSpecific() {
		ArtemisProperties properties = new ArtemisProperties();
		properties.getEmbedded().setPersistent(true);
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		String dataDir = new ApplicationTemp().getDir("artemis-data").getAbsolutePath();
		String sharedDataDir = new File(System.getProperty("java.io.tmpdir"), "artemis-data").getAbsolutePath();
		assertThat(configuration.getJournalDirectory()).startsWith(dataDir).doesNotStartWith(sharedDataDir);
	}

	@Test
	void defaultDataDirDoesNotUsePreCreatedSharedTempLocation(@TempDir Path temp) throws IOException {
		Path tempDirectory = Files.createDirectory(temp.resolve("tmp"));
		Path sharedDataDir = Files.createDirectory(tempDirectory.resolve("artemis-data"));
		Files.createDirectory(sharedDataDir.resolve("journal"));
		String previousTempDirectory = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", tempDirectory.toString());
		try {
			ArtemisProperties properties = new ArtemisProperties();
			properties.getEmbedded().setPersistent(true);
			Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
			String dataDir = new ApplicationTemp().getDir("artemis-data").getAbsolutePath();
			assertThat(dataDir).startsWith(tempDirectory.toString()).isNotEqualTo(sharedDataDir.toString());
			assertThat(configuration.getJournalDirectory()).startsWith(dataDir)
					.doesNotStartWith(sharedDataDir.toString());
			assertThat(configuration.getBindingsDirectory()).startsWith(dataDir)
					.doesNotStartWith(sharedDataDir.toString());
			assertThat(configuration.getPagingDirectory()).startsWith(dataDir)
					.doesNotStartWith(sharedDataDir.toString());
			assertThat(configuration.getLargeMessagesDirectory()).startsWith(dataDir)
					.doesNotStartWith(sharedDataDir.toString());
		}
		finally {
			System.setProperty("java.io.tmpdir", previousTempDirectory);
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void defaultDataDirIsOnlyAccessibleByOwner(@TempDir Path temp) throws IOException {
		Path tempDirectory = Files.createDirectory(temp.resolve("tmp"));
		String previousTempDirectory = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", tempDirectory.toString());
		try {
			Path dataDir = createConfigurationAndGetDataDir();
			assertThat(dataDir).isDirectory();
			assertThat(dataDir.getParent()).isEqualTo(new ApplicationTemp().getDir().toPath());
			assertOwnerOnlyPermissions(dataDir);
			assertOwnerOnlyPermissions(dataDir.getParent());
		}
		finally {
			System.setProperty("java.io.tmpdir", previousTempDirectory);
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void defaultDataDirPermissionsAreRestrictedWhenItAlreadyExists(@TempDir Path temp) throws IOException {
		Path tempDirectory = Files.createDirectory(temp.resolve("tmp"));
		String previousTempDirectory = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", tempDirectory.toString());
		try {
			Path applicationTempDirectory = new ApplicationTemp().getDir().toPath();
			Path existingDataDir = Files.createDirectory(applicationTempDirectory.resolve("artemis-data"));
			Set<PosixFilePermission> permissive = PosixFilePermissions.fromString("rwxrwxrwx");
			Files.setPosixFilePermissions(applicationTempDirectory, permissive);
			Files.setPosixFilePermissions(existingDataDir, permissive);
			Path dataDir = createConfigurationAndGetDataDir();
			assertThat(dataDir).isEqualTo(existingDataDir);
			assertOwnerOnlyPermissions(dataDir);
			assertOwnerOnlyPermissions(applicationTempDirectory);
		}
		finally {
			System.setProperty("java.io.tmpdir", previousTempDirectory);
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void defaultDataDirWhenDataDirIsSymbolicLinkThrows(@TempDir Path temp) throws IOException {
		Path tempDirectory = Files.createDirectory(temp.resolve("tmp"));
		Path attackerDirectory = Files.createDirectory(temp.resolve("attacker"));
		String previousTempDirectory = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", tempDirectory.toString());
		try {
			Path applicationTempDirectory = new ApplicationTemp().getDir().toPath();
			Files.createSymbolicLink(applicationTempDirectory.resolve("artemis-data"), attackerDirectory);
			assertThatIllegalStateException().isThrownBy(this::createConfigurationAndGetDataDir)
					.withMessageContaining("symbolic link");
			assertThat(attackerDirectory.toFile().list()).isEmpty();
		}
		finally {
			System.setProperty("java.io.tmpdir", previousTempDirectory);
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void defaultDataDirWhenApplicationTempIsSymbolicLinkThrows(@TempDir Path temp) throws IOException {
		Path tempDirectory = Files.createDirectory(temp.resolve("tmp"));
		Path attackerDirectory = Files.createDirectory(temp.resolve("attacker"));
		String previousTempDirectory = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", tempDirectory.toString());
		try {
			Path applicationTempDirectory = new ApplicationTemp().getDir().toPath();
			Files.delete(applicationTempDirectory);
			Files.createSymbolicLink(applicationTempDirectory, attackerDirectory);
			assertThatIllegalStateException().isThrownBy(this::createConfigurationAndGetDataDir)
					.withMessageContaining("symbolic link");
			assertThat(attackerDirectory.toFile().list()).isEmpty();
		}
		finally {
			System.setProperty("java.io.tmpdir", previousTempDirectory);
		}
	}

	@Test
	void persistenceSetup() {
		ArtemisProperties properties = new ArtemisProperties();
		properties.getEmbedded().setPersistent(true);
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		assertThat(configuration.isPersistenceEnabled()).isTrue();
		assertThat(configuration.getJournalType()).isEqualTo(JournalType.NIO);
	}

	@Test
	void generatedClusterPassword() {
		ArtemisProperties properties = new ArtemisProperties();
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		assertThat(configuration.getClusterPassword()).hasSize(36);
	}

	@Test
	void specificClusterPassword() {
		ArtemisProperties properties = new ArtemisProperties();
		properties.getEmbedded().setClusterPassword("password");
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		assertThat(configuration.getClusterPassword()).isEqualTo("password");
	}

	@Test
	void hasDlqExpiryQueueAddressSettingsConfigured() {
		ArtemisProperties properties = new ArtemisProperties();
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		Map<String, AddressSettings> addressesSettings = configuration.getAddressesSettings();
		assertThat((Object) addressesSettings.get("#").getDeadLetterAddress())
				.isEqualTo(SimpleString.toSimpleString("DLQ"));
		assertThat((Object) addressesSettings.get("#").getExpiryAddress())
				.isEqualTo(SimpleString.toSimpleString("ExpiryQueue"));
	}

	@Test
	void hasDlqExpiryQueueConfigured() {
		ArtemisProperties properties = new ArtemisProperties();
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		List<CoreAddressConfiguration> addressConfigurations = configuration.getAddressConfigurations();
		assertThat(addressConfigurations).hasSize(2);
	}

	private Path createConfigurationAndGetDataDir() {
		ArtemisProperties properties = new ArtemisProperties();
		properties.getEmbedded().setPersistent(true);
		Configuration configuration = new ArtemisEmbeddedConfigurationFactory(properties).createConfiguration();
		return Paths.get(configuration.getJournalDirectory()).getParent();
	}

	private void assertOwnerOnlyPermissions(Path path) throws IOException {
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
		assertThat(permissions).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	}

}
