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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.CoreQueueConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.system.ApplicationTemp;
import org.springframework.util.Assert;

/**
 * Configuration used to create the embedded Artemis server.
 *
 * @author Eddú Meléndez
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ArtemisEmbeddedConfigurationFactory {

	private static final Log logger = LogFactory.getLog(ArtemisEmbeddedConfigurationFactory.class);

	private static final String DATA_DIRECTORY_NAME = "artemis-data";

	private static final EnumSet<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

	private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = {};

	private final ArtemisProperties.Embedded properties;

	ArtemisEmbeddedConfigurationFactory(ArtemisProperties properties) {
		this.properties = properties.getEmbedded();
	}

	Configuration createConfiguration() {
		ConfigurationImpl configuration = new ConfigurationImpl();
		configuration.setSecurityEnabled(false);
		configuration.setPersistenceEnabled(this.properties.isPersistent());
		String dataDir = getDataDir();
		configuration.setJournalDirectory(dataDir + "/journal");
		if (this.properties.isPersistent()) {
			configuration.setJournalType(JournalType.NIO);
			configuration.setLargeMessagesDirectory(dataDir + "/largemessages");
			configuration.setBindingsDirectory(dataDir + "/bindings");
			configuration.setPagingDirectory(dataDir + "/paging");
		}
		TransportConfiguration transportConfiguration = new TransportConfiguration(InVMAcceptorFactory.class.getName(),
				this.properties.generateTransportParameters());
		configuration.getAcceptorConfigurations().add(transportConfiguration);
		if (this.properties.isDefaultClusterPassword() && logger.isDebugEnabled()) {
			logger.debug("Using default Artemis cluster password: " + this.properties.getClusterPassword());
		}
		configuration.setClusterPassword(this.properties.getClusterPassword());
		configuration.addAddressConfiguration(createAddressConfiguration("DLQ"));
		configuration.addAddressConfiguration(createAddressConfiguration("ExpiryQueue"));
		configuration.addAddressesSetting("#",
				new AddressSettings().setDeadLetterAddress(SimpleString.toSimpleString("DLQ"))
						.setExpiryAddress(SimpleString.toSimpleString("ExpiryQueue")));
		return configuration;
	}

	private CoreAddressConfiguration createAddressConfiguration(String name) {
		return new CoreAddressConfiguration().setName(name).addRoutingType(RoutingType.ANYCAST).addQueueConfiguration(
				new CoreQueueConfiguration().setName(name).setRoutingType(RoutingType.ANYCAST).setAddress(name));
	}

	private String getDataDir() {
		if (this.properties.getDataDirectory() != null) {
			return this.properties.getDataDirectory();
		}
		Path applicationTempDirectory = new ApplicationTemp().getDir().toPath();
		return createDataDirectory(applicationTempDirectory).toAbsolutePath().toString();
	}

	private Path createDataDirectory(Path applicationTempDirectory) {
		Path dataDirectory = applicationTempDirectory.resolve(DATA_DIRECTORY_NAME);
		try {
			UserPrincipal currentUser = getCurrentUser();
			secureDirectory(applicationTempDirectory, currentUser);
			if (!Files.exists(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
				try {
					Files.createDirectory(dataDirectory, getDirectoryAttributes(dataDirectory.getFileSystem()));
				}
				catch (FileAlreadyExistsException ex) {
					// Created concurrently, the existing directory is checked below
				}
			}
			secureDirectory(dataDirectory, currentUser);
			return dataDirectory;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to create Artemis data directory '" + dataDirectory + "'", ex);
		}
	}

	private UserPrincipal getCurrentUser() throws IOException {
		Path ownerProbe = Files.createTempFile(DATA_DIRECTORY_NAME, "-owner");
		try {
			return Files.getOwner(ownerProbe);
		}
		catch (UnsupportedOperationException ex) {
			return null;
		}
		finally {
			Files.deleteIfExists(ownerProbe);
		}
	}

	private void secureDirectory(Path directory, UserPrincipal currentUser) throws IOException {
		Assert.state(!Files.isSymbolicLink(directory), () -> "'" + directory + "' must not be a symbolic link");
		Assert.state(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
				() -> "'" + directory + "' already exists but is not a directory");
		if (currentUser != null) {
			UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
			Assert.state(currentUser.equals(owner),
					() -> "'" + directory + "' is not owned by the current user " + currentUser.getName());
		}
		PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			view.setPermissions(DIRECTORY_PERMISSIONS);
		}
	}

	private FileAttribute<?>[] getDirectoryAttributes(FileSystem fileSystem) {
		if (!fileSystem.supportedFileAttributeViews().contains("posix")) {
			return NO_FILE_ATTRIBUTES;
		}
		return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS) };
	}

}
