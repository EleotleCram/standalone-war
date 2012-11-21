/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xlson.standalonewar;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.webapp.WebAppContext;

import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.List;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starter for embedded Jetty. This class is intended to be packaged into a
 * war-file and set as the Main-Class in MANIFEST.MF. Running the war will
 * start a Jetty instance on port 8080 with the containing war loaded.
 * If you specify a port environment variable that will be used for Jetty
 * instead of 8080 (see example).
 * <p/>
 * The base of this class comes from this blogpost:
 * http://eclipsesource.com/blogs/2009/10/02/executable-wars-with-jetty/
 * <p/>
 * Example:
 * java -jar -Dwebserver.listenPort=80 webapp.war
 * <p/>
 * webapp.war is loaded on http://localhost/
 *
 * @author Leonard Axelsson
 * @author Joris Koster
 * @author Marcel Toele
 */
@SuppressWarnings({"unchecked","deprecation"}) // Tell the compiler to shut up: the old API is simpler and better!
public class Starter {

	private static Server server;
	private static PropertiesConfiguration defaultProperties;
	private static PropertiesConfiguration config;
	private static Logger logger = LoggerFactory.getLogger(Starter.class);

	private static String getString(String propertyName) {
		return getString(propertyName, null);
	}

	private static String getString(String propertyName, String defaultValue) {
		return System.getProperty(propertyName, config.getString(propertyName, defaultValue));
	}

	private static Integer getInteger(String propertyName, Integer defaultValue) {
		return Integer.getInteger(propertyName, config.getInteger(propertyName, defaultValue));
	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		try {
			defaultProperties = new PropertiesConfiguration(Starter.class.getClassLoader().getResource("webserver.properties").toURI().toURL());

			appendClasspath(defaultProperties.getList("webserver.extraClasspath"));

			String configFilename = defaultProperties.getString("webserver.configFilename", "webserver.conf");
			try {
				config = new PropertiesConfiguration(configFilename);
			} catch(ConfigurationException e) {
				config = new PropertiesConfiguration();
			}

			final String PROP_NAME_WEBSERVER_TIMEOUT = defaultProperties.getString("PROP_NAME_WEBSERVER_TIMEOUT", "webserver.timeout");
			int timeout = Integer.parseInt(getString(PROP_NAME_WEBSERVER_TIMEOUT, "0"));
			start();
			if (timeout != 0) {
				Thread.sleep(timeout);
				server.stop();
				server.join();
			}
		} catch (Exception ex) {
			logger.error("error", ex);
			System.err.println(ex.getMessage());
			System.exit(-1);
		}
	}

	public static void start() throws Exception {
		final String PROP_NAME_WEBSERVER_BIND_ADDRESS = defaultProperties.getString("PROP_NAME_WEBSERVER_BIND_ADDRESS", "webserver.bindAddress");
		final String PROP_NAME_WEBSERVER_LISTENPORT = defaultProperties.getString("PROP_NAME_WEBSERVER_LISTENPORT", "webserver.listenPort");
		final String PROP_NAME_WEBSERVER_TEMPDIR = defaultProperties.getString("PROP_NAME_WEBSERVER_TEMPDIR", "webserver.tempDir");
		final String PROP_NAME_WEBSERVER_SSL = defaultProperties.getString("PROP_NAME_WEBSERVER_SSL", "webserver.ssl");
		final String PROP_NAME_WEBSERVER_SSL_LISTENPORT = defaultProperties.getString("PROP_NAME_WEBSERVER_SSL_LISTENPORT", "webserver.ssl.listenPort");
		final String PROP_NAME_WEBSERVER_SSL_KEYSTORE_TYPE = defaultProperties.getString("PROP_NAME_WEBSERVER_SSL_KEYSTORE_TYPE", "webserver.ssl.keyStoreType");
		final String PROP_NAME_WEBSERVER_SSL_KEYSTORE_FILE = defaultProperties.getString("PROP_NAME_WEBSERVER_SSL_KEYSTORE_FILE", "webserver.ssl.keyStoreFile");
		final String PROP_NAME_WEBSERVER_SSL_KEYSTORE_PASSWORD = defaultProperties.getString("PROP_NAME_WEBSERVER_SSL_KEYSTORE_PASSWORD", "webserver.ssl.keyStorePassword");

		String tempPath = getString(PROP_NAME_WEBSERVER_TEMPDIR, System.getProperty("java.io.tmpdir"));
		logger.info("using '" + tempPath + "' as temporary directory");

		server = new Server();

		String bindAddress = getString(PROP_NAME_WEBSERVER_BIND_ADDRESS, "0.0.0.0");

		if ("true".equalsIgnoreCase(getString(PROP_NAME_WEBSERVER_SSL))) {
			/* Note: the default keystore file type for war-launcher is PKCS12, you create a PKCS12 key store file with:
			 *
			 *  1) OpenSSL: openssl pkcs12 -export -clcerts -in example.com.crt -inkey example.com.key -out example.com.p12
			 *  2) keytool: @TODO
			 */
			String keyStoreType = getString(PROP_NAME_WEBSERVER_SSL_KEYSTORE_TYPE, "PKCS12");
			logger.info("using '" + keyStoreType + "' as key store type");

			String keyStoreFile = getString(PROP_NAME_WEBSERVER_SSL_KEYSTORE_FILE, null);
			if (keyStoreFile == null) {
				throw new RuntimeException("ssl requires a property value for: " + PROP_NAME_WEBSERVER_SSL_KEYSTORE_FILE);
			} else {
				logger.info("using '" + keyStoreFile + "' as key store file");
			}

			String keyStorePassword = getString(PROP_NAME_WEBSERVER_SSL_KEYSTORE_PASSWORD);

			int defaultSslListenPort = Integer.parseInt(defaultProperties.getString("webserver.ssl.defaultListenPort", "8443"));
			String sslListenPort = getString(PROP_NAME_WEBSERVER_SSL_LISTENPORT, null);
			int sslPort;
			if (sslListenPort == null) {
				logger.warn("'" + PROP_NAME_WEBSERVER_SSL_LISTENPORT + "' was not specified, using the default port.");
				sslPort = defaultSslListenPort;
			} else {
				sslPort = Integer.decode(sslListenPort);
				logger.info("using port '" + sslPort + "' for ssl connections");
			}

			SslSelectChannelConnector connector = new SslSelectChannelConnector();
			try {
				connector.setKeystoreType(keyStoreType);
				connector.setKeystore(keyStoreFile);
				connector.setPassword(keyStorePassword);
				connector.setKeyPassword(keyStorePassword);
				connector.setHost(bindAddress);
				connector.setPort(sslPort);

				logger.info("Adding HTTPS connector for " + bindAddress + ":" + sslPort);
				server.addConnector(connector);
			} catch (Exception ex) {
				logger.error("error", ex);
			}
		}

		int defaultListenPort = Integer.parseInt(defaultProperties.getString("webserver.defaultListenPort", "8080"));
		String listenPort = getString(PROP_NAME_WEBSERVER_LISTENPORT, null);
		if ((listenPort != null) || (server.getConnectors() == null)) {
			SocketConnector connector = new SocketConnector();

			if (listenPort != null) {
				logger.warn("'" + PROP_NAME_WEBSERVER_LISTENPORT + "' was not specified, using the default port.");
			}

			int port = getInteger(PROP_NAME_WEBSERVER_LISTENPORT, defaultListenPort);
			connector.setHost(bindAddress);
			connector.setPort(port);
			connector.setMaxIdleTime(1000 * 60 * 60);
			connector.setSoLingerTime(-1);

			logger.info("Adding HTTP connector for " + bindAddress + ":" + port);
			server.addConnector(connector);
		} else {
			logger.warn("'" + PROP_NAME_WEBSERVER_LISTENPORT
					+ "' was not specified, but other connectors already registered, so no default connector was added for port "
					+ defaultListenPort);
		}

		WebAppContext webapp = new WebAppContext();
		String extraClasspath = getString("webserver.extraClasspath", "");

		logger.info("Setting extra classpath for webapp: {}", extraClasspath);

		webapp.setExtraClasspath(extraClasspath);
		webapp.setContextPath("/");
		webapp.setTempDirectory(new File(tempPath));
		webapp.setServer(server);

		ProtectionDomain protectionDomain = Starter.class.getProtectionDomain();
		URL location = protectionDomain.getCodeSource().getLocation();

		System.setProperty(Starter.class.getPackage().getName() + ".warLocation", location.toString());
		logger.debug("War location: " + location);

		webapp.setWar(location.toExternalForm());

		server.setHandler(webapp);
		server.start();
	}

	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
			server.join();
		}
	}

	/**
	 * Add paths to the system class loader.
	 * (The paths are expected to be comma-separated.)
	 */
	public static void appendClasspath(List<String> paths) throws IOException {
		for(String path : paths) {
			path = path.replaceAll("~", System.getProperty("user.home")) + File.separator;
			URL url;
			try {
				url = new URL(path);
			} catch(MalformedURLException e) {
				url = new URL("file://"+path);
			}
			logger.info("Appending the following path to the system class path: " + url);
			appendClasspath(url);
		}
	}
	private static final Class[] parameters = new Class[]{URL.class};
	/**
	 * Add url to the system class loader.
	 */
	public static void appendClasspath(URL url) throws IOException {

		URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();
		Class sysclass = URLClassLoader.class;

		try {
			Method method = sysclass.getDeclaredMethod("addURL", parameters);
			method.setAccessible(true);
			method.invoke(sysloader, new Object[]{url});
		} catch (Throwable t) {
			t.printStackTrace();
			throw new IOException("Error, could not add URL to system classloader");
		}

	}
}
