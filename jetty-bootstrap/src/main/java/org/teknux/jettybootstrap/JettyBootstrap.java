/*******************************************************************************
 * (C) Copyright 2014 Teknux.org (http://teknux.org/).
 *  
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
 *  
 * Contributors:
 *      "Pierre PINON"
 *      "Francois EYL"
 *      "Laurent MARCHAL"
 *  
 *******************************************************************************/
package org.teknux.jettybootstrap;

import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teknux.jettybootstrap.configuration.IJettyConfiguration;
import org.teknux.jettybootstrap.configuration.JettyConnector;
import org.teknux.jettybootstrap.configuration.PropertiesJettyConfiguration;
import org.teknux.jettybootstrap.handler.ExplodedWarAppJettyHandler;
import org.teknux.jettybootstrap.handler.JettyHandler;
import org.teknux.jettybootstrap.handler.WarAppFromClasspathJettyHandler;
import org.teknux.jettybootstrap.handler.WarAppJettyHandler;
import org.teknux.jettybootstrap.keystore.JettyKeystoreConvertorBuilder;
import org.teknux.jettybootstrap.keystore.JettyKeystoreException;
import org.teknux.jettybootstrap.keystore.JettyKeystoreGeneratorBuilder;
import org.teknux.jettybootstrap.utils.PathUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;


/**
 * Main class for easily boostrapping jetty.
 */
public class JettyBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(JettyBootstrap.class);

    private static final String DEFAULT_KEYSTORE_FILENAME = "default.keystore";

    private static final String TEMP_DIRECTORY_NAME = ".temp";
    public static final File TEMP_DIRECTORY_JARDIR = new File(PathUtil.getJarDir() + File.separator + TEMP_DIRECTORY_NAME);
    public static final File TEMP_DIRECTORY_SYSTEMP = new File(System.getProperty("java.io.tmpdir") + File.separator + TEMP_DIRECTORY_NAME);
    protected static final File TEMP_DIRECTORY_DEFAULT = TEMP_DIRECTORY_JARDIR;

    public static final String RESOURCE_WEBAPP = "/webapp";
    public static final String CONTEXT_PATH_ROOT = "/";

    private final IJettyConfiguration iJettyConfiguration;
    private boolean isInitializedConfiguration = false;

    private Server server = null;
    private final HandlerList handlers = new HandlerList();

    /**
     * Shortcut to start Jetty when called within a JAR file containing the WEB-INF folder and needed libraries.
     * <p>
     * Basically uses {@link #addSelf()} and {@link #startServer()}
     * 
     * @return a new instance of {@link JettyBootstrap}
     * @throws JettyBootstrapException
     *             if an error occurs during the startup
     */
    public static JettyBootstrap startSelf() throws JettyBootstrapException {
        final JettyBootstrap jettyBootstrap = new JettyBootstrap();
        jettyBootstrap.addSelf();
        return jettyBootstrap.startServer();
    }

    /**
     * Default constructor using the default {@link PropertiesJettyConfiguration} configuration.
     */
    public JettyBootstrap() {
        this(null);
    }

    /**
     * Constructor specifiying the configuration properties.
     * 
     * @param iJettyconfiguration
     *            the {@link IJettyConfiguration} implementation of the configuration
     */
    public JettyBootstrap(final IJettyConfiguration iJettyconfiguration) {
        if (iJettyconfiguration == null) {
            this.iJettyConfiguration = new PropertiesJettyConfiguration();
        } else {
            this.iJettyConfiguration = iJettyconfiguration.clone();
        }
    }

    /**
     * Starts the Jetty Server and join the calling thread according to {@link IJettyConfiguration#isAutoJoinOnStart()}
     * 
     * @return this instance
     * @throws JettyBootstrapException
     *             if an exception occurs during the initialization
     * @see #startServer(Boolean)
     */
    public JettyBootstrap startServer() throws JettyBootstrapException {
        return startServer(null);
    }

    /**
     * Starts the Jetty Server and join the calling thread.
     * 
     * @param join
     *            <code>true</code> to block the calling thread until the server stops. <code>false</code> otherwise
     * @return this instance
     * @throws JettyBootstrapException
     *             if an exception occurs during the initialization
     */
    public JettyBootstrap startServer(Boolean join) throws JettyBootstrapException {
        LOG.info("Starting Server...");

        IJettyConfiguration iJettyConfiguration = getInitializedConfiguration();
        initServer(iJettyConfiguration);

        try {
            server.start();
        } catch (Exception e) {
            throw new JettyBootstrapException(e);
        }

        // display server addresses
        if (iJettyConfiguration.getJettyConnectors().contains(JettyConnector.HTTP)) {
            LOG.info("http://{}:{}", iJettyConfiguration.getHost(), iJettyConfiguration.getPort());
        }
        if (iJettyConfiguration.getJettyConnectors().contains(JettyConnector.HTTPS)) {
            LOG.info("https://{}:{}", iJettyConfiguration.getHost(), iJettyConfiguration.getSslPort());
        }

        if ((join != null && join) || (join == null && iJettyConfiguration.isAutoJoinOnStart())) {
            joinServer();
        }

        return this;
    }

    /**
     * Blocks the calling thread until the server stops.
     * 
     * @return this instance
     * @throws JettyBootstrapException
     *             if an exception occurs while blocking the thread
     */
    public JettyBootstrap joinServer() throws JettyBootstrapException {
        try {
            if (isServerStarted()) {
                LOG.debug("Joining Server...");

                server.join();
            } else {
                LOG.warn("Can't join Server. Not started");
            }
        } catch (InterruptedException e) {
            throw new JettyBootstrapException(e);
        }

        return this;
    }

    /**
     * Return if server is started
     * 
     * @return if server is started
     */
    public boolean isServerStarted() {
        return (server != null && server.isStarted());
    }

    /**
     * Stops the Jetty server.
     * 
     * @return this instance
     * @throws JettyBootstrapException
     *             if an exception occurs while stopping the server or if the server is not started
     */
    public JettyBootstrap stopServer() throws JettyBootstrapException {
        LOG.info("Stopping Server...");
        try {
            if (isServerStarted()) {
                handlers.stop();

                server.stop();

                LOG.info("Server stopped.");
            } else {
                LOG.warn("Can't stop server. Already stopped");
            }
        } catch (Exception e) {
            throw new JettyBootstrapException(e);
        }

        return this;
    }

    /**
     * Add a War application the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @param war
     *            the path to a war file
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failure
     */
    public WebAppContext addWarApp(String war) throws JettyBootstrapException {
        return addWarApp(war, CONTEXT_PATH_ROOT);
    }

    /**
     * Add a War application specifying the context path.
     * 
     * @param war
     *            the path to a war file
     * @param contextPath
     *            the path (base URL) to make the war available
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failure
     */
    public WebAppContext addWarApp(String war, String contextPath) throws JettyBootstrapException {
        WarAppJettyHandler warAppJettyHandler = new WarAppJettyHandler(getInitializedConfiguration());
        warAppJettyHandler.setWar(war);
        warAppJettyHandler.setContextPath(contextPath);

        WebAppContext webAppContext = warAppJettyHandler.getHandler();
        handlers.addHandler(webAppContext);

        return webAppContext;
    }

    /**
     * Add a War application from the current classpath on the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @param warFromClasspath
     *            the path to a war file in the classpath
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addWarAppFromClasspath(String warFromClasspath) throws JettyBootstrapException {
        return addWarAppFromClasspath(warFromClasspath, CONTEXT_PATH_ROOT);
    }

    /**
     * Add a War application from the current classpath specifying the context path.
     * 
     * @param warFromClasspath
     *            the path to a war file in the classpath
     * @param contextPath
     *            the path (base URL) to make the war available
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addWarAppFromClasspath(String warFromClasspath, String contextPath) throws JettyBootstrapException {
        WarAppFromClasspathJettyHandler warAppFromClasspathJettyHandler = new WarAppFromClasspathJettyHandler(getInitializedConfiguration());
        warAppFromClasspathJettyHandler.setWarFromClasspath(warFromClasspath);
        warAppFromClasspathJettyHandler.setContextPath(contextPath);

        WebAppContext webAppContext = warAppFromClasspathJettyHandler.getHandler();
        handlers.addHandler(webAppContext);

        return webAppContext;
    }

    /**
     * Add an exploded (not packaged) War application on the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @param explodedWar
     *            the exploded war path
     * @param descriptor
     *            the web.xml descriptor path
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addExplodedWarApp(String explodedWar, String descriptor) throws JettyBootstrapException {
        return addExplodedWarApp(explodedWar, descriptor, CONTEXT_PATH_ROOT);
    }

    /**
     * Add an exploded (not packaged) War application specifying the context path.
     * 
     * @param explodedWar
     *            the exploded war path
     * @param descriptor
     *            the web.xml descriptor path
     * @param contextPath
     *            the path (base URL) to make the resource available
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addExplodedWarApp(String explodedWar, String descriptor, String contextPath) throws JettyBootstrapException {
        ExplodedWarAppJettyHandler explodedWarAppJettyHandler = new ExplodedWarAppJettyHandler(getInitializedConfiguration());
        explodedWarAppJettyHandler.setWebAppBase(explodedWar);
        explodedWarAppJettyHandler.setDescriptor(descriptor);
        explodedWarAppJettyHandler.setContextPath(contextPath);

        WebAppContext webAppContext = explodedWarAppJettyHandler.getHandler();
        handlers.addHandler(webAppContext);

        return webAppContext;
    }

    /**
     * Add an exploded (not packaged) War application from the current classpath, on the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @param explodedWar
     *            the exploded war path
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addExplodedWarAppFromClasspath(String explodedWar) throws JettyBootstrapException {
        return addExplodedWarAppFromClasspath(explodedWar, null);
    }

    /**
     * Add an exploded (not packaged) War application from the current classpath, on the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @param explodedWar
     *            the exploded war path
     * @param descriptor
     *            the web.xml descriptor path
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addExplodedWarAppFromClasspath(String explodedWar, String descriptor) throws JettyBootstrapException {
        return addExplodedWarAppFromClasspath(explodedWar, descriptor, CONTEXT_PATH_ROOT);
    }

    /**
     * Add an exploded (not packaged) War application from the current classpath, specifying the context path.
     * 
     * @param explodedWar
     *            the exploded war path
     * @param descriptor
     *            the web.xml descriptor path
     * @param contextPath
     *            the path (base URL) to make the resource available
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addExplodedWarAppFromClasspath(String explodedWar, String descriptor, String contextPath) throws JettyBootstrapException {
        ExplodedWarAppJettyHandler explodedWarAppJettyHandler = new ExplodedWarAppJettyHandler(getInitializedConfiguration());
        explodedWarAppJettyHandler.setWebAppBaseFromClasspath(explodedWar);
        explodedWarAppJettyHandler.setDescriptor(descriptor);
        explodedWarAppJettyHandler.setContextPath(contextPath);

        WebAppContext webAppContext = explodedWarAppJettyHandler.getHandler();
        handlers.addHandler(webAppContext);

        return webAppContext;
    }

    /**
     * Add an exploded War application found from {@value #RESOURCE_WEBAPP} in the current classpath on the default context path {@value #CONTEXT_PATH_ROOT}
     * 
     * @see #addExplodedWarAppFromClasspath(String, String)
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addSelf() throws JettyBootstrapException {
        return addExplodedWarAppFromClasspath(RESOURCE_WEBAPP, null);
    }

    /**
     * Add an exploded War application found from {@value #RESOURCE_WEBAPP} in the current classpath specifying the context path.
     * 
     * @see #addExplodedWarAppFromClasspath(String, String, String)
     * @param contextPath
     *            the path (base URL) to make the resource available
     * @return WebAppContext
     * @throws JettyBootstrapException
     *             on failed
     */
    public WebAppContext addSelf(String contextPath) throws JettyBootstrapException {
        return addExplodedWarAppFromClasspath(RESOURCE_WEBAPP, null, contextPath);
    }

    /**
     * Add Handler
     * 
     * @param handler
     *            Jetty Handler
     * @return Handler
     * @throws JettyBootstrapException
     *             on failed
     */
    public Handler addHandler(Handler handler) throws JettyBootstrapException {
        JettyHandler jettyHandler = new JettyHandler();
        jettyHandler.setHandler(handler);

        handlers.addHandler(handler);

        return handler;
    }

    /**
     * Get the jetty {@link Server} Object. Calls {@link #initServer(IJettyConfiguration)} if not initialized yet.
     * 
     * @return the contained jetty {@link Server} Object.
     * @throws JettyBootstrapException
     *             if an error occurs during {@link #initServer(IJettyConfiguration)}
     */
    public Server getServer() throws JettyBootstrapException {
        initServer(getInitializedConfiguration());

        return server;
    }

    /**
     * Initialize Jetty server using the given {@link IJettyConfiguration}. Basically creates the server, set connectors, handlers and adds the shutdown hook.
     *
     * @param iJettyConfiguration
     *            Jetty Configuration
     * @throws JettyBootstrapException
     *             on failure
     */
    protected void initServer(IJettyConfiguration iJettyConfiguration) throws JettyBootstrapException {
        if (server == null) {
            server = createServer(iJettyConfiguration);
            server.setConnectors(createConnectors(iJettyConfiguration, server));

            server.setHandler(handlers);

            if (iJettyConfiguration.isStopAtShutdown()) {
                createShutdownHook();
            }
        }
    }

    /**
     * Parse the {@link IJettyConfiguration}, validate the configuration and initialize it if necessary. Clean temp directory if necessary and generates SSL keystore when
     * necessary.
     * 
     * @return IJettyConfiguration Jetty Configuration
     * @throws JettyBootstrapException
     *             on failure
     */
    protected IJettyConfiguration getInitializedConfiguration() throws JettyBootstrapException {
        if (!isInitializedConfiguration) {
            LOG.debug("Init Configuration...");

            LOG.trace("Check Temp Directory...");
            if (iJettyConfiguration.getTempDirectory() == null) {
                iJettyConfiguration.setTempDirectory(TEMP_DIRECTORY_DEFAULT);
            }

            if (iJettyConfiguration.getTempDirectory().exists() && iJettyConfiguration.isCleanTempDir()) {
                LOG.trace("Clean Temp Directory...");

                try {
                    FileUtils.deleteDirectory(iJettyConfiguration.getTempDirectory());
                } catch (IOException e) {
                    throw new JettyBootstrapException("Can't clean temporary directory");
                }
            }
            if (!iJettyConfiguration.getTempDirectory().exists() && !iJettyConfiguration.getTempDirectory().mkdirs()) {
                throw new JettyBootstrapException("Can't create temporary directory");
            }

            LOG.trace("Check required properties...");
            if (iJettyConfiguration.getHost() == null || iJettyConfiguration.getHost().isEmpty()) {
                throw new JettyBootstrapException("Host not specified");
            }

            LOG.trace("Check connectors...");
            if (iJettyConfiguration.hasJettyConnector(JettyConnector.HTTPS)) {

                //Checks keystore path only if keyStore object and SSL private key or SSL certificate are not specified
                if (iJettyConfiguration.getSslKeyStore() == null &&
                    (iJettyConfiguration.getSslPrivateKeyPath() == null || iJettyConfiguration.getSslPrivateKeyPath().isEmpty() ||
                        iJettyConfiguration.getSslCertificatePath() == null || iJettyConfiguration.getSslCertificatePath().isEmpty())) {

                    //If keystore path is not specified, use default keystore path
                    if (iJettyConfiguration.getSslKeyStorePath() == null || iJettyConfiguration.getSslKeyStorePath().isEmpty()) {
                        iJettyConfiguration.setSslKeyStorePath(iJettyConfiguration.getTempDirectory().getPath() + File.separator + DEFAULT_KEYSTORE_FILENAME);
                    }

                    //Create keystore file if not exits
                    File keystoreFile = new File(iJettyConfiguration.getSslKeyStorePath());
                    if (!keystoreFile.exists()) {
                        try {
                            JettyKeystoreGeneratorBuilder jettyKeystoreGeneratorBuilder = new JettyKeystoreGeneratorBuilder();
                            jettyKeystoreGeneratorBuilder.setAlgorithm(iJettyConfiguration.getSslKeyStoreAlgorithm());
                            jettyKeystoreGeneratorBuilder.setSignatureAlgorithm(iJettyConfiguration.getSslKeyStoreSignatureAlgorithm());
                            jettyKeystoreGeneratorBuilder.setRdnOuValue(iJettyConfiguration.getSslKeyStoreRdnOuValue());
                            jettyKeystoreGeneratorBuilder.setRdnOValue(iJettyConfiguration.getSslKeyStoreRdnOValue());
                            jettyKeystoreGeneratorBuilder.setDateNotBeforeNumberOfDays(iJettyConfiguration.getSslKeyStoreDateNotBeforeNumberOfDays());
                            jettyKeystoreGeneratorBuilder.setDateNotAfterNumberOfDays(iJettyConfiguration.getSslKeyStoreDateNotAfterNumberOfDays());

                            KeyStore keyStore = jettyKeystoreGeneratorBuilder.build(iJettyConfiguration.getSslKeyStoreDomainName(), iJettyConfiguration.getSslKeyStoreAlias(),
                                    iJettyConfiguration.getSslKeyStorePassword());
                            JettyKeystoreGeneratorBuilder.saveKeyStore(keyStore, keystoreFile, iJettyConfiguration.getSslKeyStorePassword());
                        } catch (JettyKeystoreException e) {
                            throw new JettyBootstrapException("Can't generate keyStore", e);
                        }
                    }
                }
            }

            if (iJettyConfiguration.isRedirectWebAppsOnHttpsConnector() &&
                (!iJettyConfiguration.hasJettyConnector(JettyConnector.HTTP) || !iJettyConfiguration.hasJettyConnector(JettyConnector.HTTPS))) {
                throw new JettyBootstrapException("You can't redirect all from HTTP to HTTPS Connector if both connectors are not setted");
            }

            isInitializedConfiguration = true;

            LOG.trace("Configuration : {}", iJettyConfiguration);
        }

        return iJettyConfiguration;
    }

    /**
     * Convenient method used to build and return a new {@link Server}.
     * 
     * @param iJettyConfiguration
     *            Jetty Configuration
     * @return Server
     */
    protected Server createServer(IJettyConfiguration iJettyConfiguration) {
        LOG.trace("Create Jetty Server...");

        Server server = new Server(new QueuedThreadPool(iJettyConfiguration.getMaxThreads()));
        server.setStopAtShutdown(false); // Reimplemented. See
                                         // @IJettyConfiguration.stopAtShutdown
        server.setStopTimeout(iJettyConfiguration.getStopTimeout());

        return server;
    }

    /**
     * Creates and returns the necessary {@link ServerConnector} based on the given {@link IJettyConfiguration}.
     * 
     * @param iJettyConfiguration
     *            Jetty Configuration
     * @param server
     *            the server to
     * @return Connector[]
     * @throws JettyBootstrapException
     */
    protected Connector[] createConnectors(IJettyConfiguration iJettyConfiguration, Server server) throws JettyBootstrapException {
        LOG.trace("Creating Jetty Connectors...");

        List<Connector> connectors = new ArrayList<>();

        if (iJettyConfiguration.hasJettyConnector(JettyConnector.HTTP)) {
            LOG.trace("Adding HTTP Connector...");

            ServerConnector serverConnector;

            if (iJettyConfiguration.hasJettyConnector(JettyConnector.HTTPS)) {
                HttpConfiguration httpConfiguration = new HttpConfiguration();
                httpConfiguration.setSecurePort(iJettyConfiguration.getSslPort());
                httpConfiguration.setSecureScheme(HttpScheme.HTTPS.asString());
                httpConfiguration.setIdleTimeout(iJettyConfiguration.getIdleTimeout());
                httpConfiguration.setBlockingTimeout(iJettyConfiguration.getBlockingTimeout());

                HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfiguration);

                serverConnector = new ServerConnector(server, httpConnectionFactory);
            } else {
                serverConnector = new ServerConnector(server);

                serverConnector.getConnectionFactories().stream()
                        .filter(HttpConnectionFactory.class::isInstance)
                        .map(HttpConnectionFactory.class::cast)
                        .forEach(httpConnectionFactory -> httpConnectionFactory.getHttpConfiguration().setBlockingTimeout(iJettyConfiguration.getBlockingTimeout()));
            }

            serverConnector.setIdleTimeout(iJettyConfiguration.getIdleTimeout());
            serverConnector.setHost(iJettyConfiguration.getHost());
            serverConnector.setPort(iJettyConfiguration.getPort());

            connectors.add(serverConnector);
        }
        if (iJettyConfiguration.hasJettyConnector(JettyConnector.HTTPS)) {
            LOG.trace("Adding HTTPS Connector...");

            SslContextFactory sslContextFactory = new SslContextFactory();

            if (iJettyConfiguration.getSslKeyStore() != null) { //Use keyStore object if available
                sslContextFactory.setKeyStore(iJettyConfiguration.getSslKeyStore());
            } else if (iJettyConfiguration.getSslPrivateKeyPath() != null && !iJettyConfiguration.getSslPrivateKeyPath().isEmpty() &&
                iJettyConfiguration.getSslCertificatePath() != null && !iJettyConfiguration.getSslCertificatePath().isEmpty()) { //Use private key and certificate if available

                JettyKeystoreConvertorBuilder jettyKeystoreConvertorBuilder = new JettyKeystoreConvertorBuilder();
                try {
                    File sslPrivateKeyFile = new File(iJettyConfiguration.getSslPrivateKeyPath());
                    if (!sslPrivateKeyFile.exists() || !sslPrivateKeyFile.canRead()) {
                        throw new JettyBootstrapException("Private key not exists or unreadable");
                    }

                    File sslCertificateFile = new File(iJettyConfiguration.getSslCertificatePath());
                    if (!sslCertificateFile.exists() || !sslCertificateFile.canRead()) {
                        throw new JettyBootstrapException("Certificate not exists or unreadable");
                    }

                    try (InputStream sslPrivateKeyInputStream = new FileInputStream(sslPrivateKeyFile);
                            InputStream sslCertificateInputStream = new FileInputStream(sslCertificateFile)) {

                        switch (iJettyConfiguration.getSslCertificateFormat()) {
                            case PKCS8:
                                jettyKeystoreConvertorBuilder.setCertificateFromPKCS8(sslCertificateInputStream);
                                break;
                            case PKCS12:
                                jettyKeystoreConvertorBuilder.setCertificateFromPKCS12(sslCertificateInputStream, iJettyConfiguration.getSslCertificatePassword());
                                break;
                            case UNKNOWN:
                                throw new JettyBootstrapException("Unknown Certificate Format");
                            default:
                                throw new JettyBootstrapException("Certificate Format not setted");
                        }

                        switch (iJettyConfiguration.getSslPrivateKeyFormat()) {
                            case PKCS8:
                                jettyKeystoreConvertorBuilder.setPrivateKeyFromPKCS8(sslPrivateKeyInputStream);
                                break;
                            case PKCS12:
                                jettyKeystoreConvertorBuilder.setPrivateKeyFromPKCS12(sslPrivateKeyInputStream, iJettyConfiguration.getSslPrivateKeyPassword());
                                break;
                            case UNKNOWN:
                                throw new JettyBootstrapException("Unknown Private key Format");
                            default:
                                throw new JettyBootstrapException("Private key Format not setted");
                        }
                    }

                    KeyStore keyStore = jettyKeystoreConvertorBuilder.build(iJettyConfiguration.getSslKeyStoreAlias(), iJettyConfiguration.getSslKeyStorePassword());

                    sslContextFactory.setKeyStore(keyStore);
                } catch (JettyKeystoreException | IOException e) {
                    throw new JettyBootstrapException("Can not load SSL private key or SSL certificate", e);
                }
            } else { //Use keystore path
                sslContextFactory.setKeyStorePath(iJettyConfiguration.getSslKeyStorePath());
            }
            sslContextFactory.setKeyStorePassword(iJettyConfiguration.getSslKeyStorePassword());
            ServerConnector serverConnector = new ServerConnector(server, sslContextFactory);

            serverConnector.setIdleTimeout(iJettyConfiguration.getIdleTimeout());
            serverConnector.setHost(iJettyConfiguration.getHost());
            serverConnector.setPort(iJettyConfiguration.getSslPort());

            connectors.add(serverConnector);
        }

        return connectors.toArray(new Connector[connectors.size()]);
    }

    /**
     * Create Shutdown Hook.
     */
    private void createShutdownHook() {
        LOG.trace("Creating Jetty ShutdownHook...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                LOG.debug("Shutting Down...");
                stopServer();
            } catch (Exception e) {
                LOG.error("Shutdown", e);
            }
        }));
    }
}
