/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.tyrus.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleReference;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.SynchronousBundleListener;

/**
 * Taken from Jersey 2. ServiceFinder functionality was intentionally
 * stripped down (not needed yet, might be re-added later).
 *
 * Utility class to deal with OSGi runtime specific behavior.
 * This is mainly to handle META-INF/services lookup
 * and generic/application class lookup issue in OSGi.
 *
 * @author Jakub Podlesak (jakub.podlesak at oracle.com)
 */
public final class OsgiRegistry implements SynchronousBundleListener {

    private static final String BundleSymbolicNAME = "org.glassfish.tyrus.server";
    private static final Logger LOGGER = Logger.getLogger(OsgiRegistry.class.getName());

    private final BundleContext bundleContext;
    private final Map<Long, Map<String, Callable<List<Class<?>>>>> factories =
                                                        new HashMap<Long, Map<String, Callable<List<Class<?>>>>>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static OsgiRegistry instance;

    private Map<String, Bundle> classToBundleMapping = new HashMap<String, Bundle>();

    /**
     * Returns an {@link OsgiRegistry} instance.
     *
     * @return an {@link OsgiRegistry} instance or {@code null} if the class cannot be instantiated (not in OSGi environment).
     */
    public static OsgiRegistry getOsgiRegistryInstance() {
        try {
            final Class<?> bundleReferenceClass = Class.forName("org.osgi.framework.BundleReference");

            if (bundleReferenceClass != null) {
                return OsgiRegistry.getInstance();
            }
        } catch (Exception e) {
            // Do nothing - instance is null.
        }

        return null;
    }

    /**
     * Returns an {@code OsgiRegistry} instance. Call this method only if sure that the application is running in OSGi
     * environment, otherwise a call to this method can lead to an {@link ClassNotFoundException}.
     *
     * @return an {@code OsgiRegistry} instance.
     */
    private static synchronized OsgiRegistry getInstance() {
        if (instance == null) {
            final ClassLoader classLoader = OsgiRegistry.class.getClassLoader();
            if (classLoader instanceof BundleReference) {
                BundleContext context = FrameworkUtil.getBundle(OsgiRegistry.class).getBundleContext();
                if (context != null) { // context could be still null in GlassFish
                    instance = new OsgiRegistry(context);
                }
            }
        }
        return instance;
    }

    private static class BundleSpiProvidersLoader implements Callable<List<Class<?>>> {

        private final String spi;
        private final URL spiRegistryUrl;
        private final Bundle bundle;

        BundleSpiProvidersLoader(final String spi, final URL spiRegistryUrl, final Bundle bundle) {
            this.spi = spi;
            this.spiRegistryUrl = spiRegistryUrl;
            this.bundle = bundle;
        }

        @Override
        public List<Class<?>> call() throws Exception {
            try {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "Loading providers for SPI: {0}", spi);
                }
                final BufferedReader br = new BufferedReader(new InputStreamReader(spiRegistryUrl.openStream(), "UTF-8"));
                String providerClassName;
                final List<Class<?>> providerClasses = new ArrayList<Class<?>>();
                while ((providerClassName = br.readLine()) != null) {
                    if (providerClassName.trim().length() == 0) {
                        continue;
                    }
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "SPI provider: {0}", providerClassName);
                    }
                    providerClasses.add(bundle.loadClass(providerClassName));
                }
                br.close();
                return providerClasses;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, LocalizationMessages.EXCEPTION_CAUGHT_WHILE_LOADING_SPI_PROVIDERS(), e);
                throw e;
            } catch (Error e) {
                LOGGER.log(Level.WARNING, LocalizationMessages.ERROR_CAUGHT_WHILE_LOADING_SPI_PROVIDERS(), e);
                throw e;
            }
        }

        @Override
        public String toString() {
            return spiRegistryUrl.toString();
        }

        @Override
        public int hashCode() {
            return spiRegistryUrl.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof BundleSpiProvidersLoader) {
                return spiRegistryUrl.equals(((BundleSpiProvidersLoader) obj).spiRegistryUrl);
            } else {
                return false;
            }
        }
    }


    @Override
    public void bundleChanged(BundleEvent event) {

        if (event.getType() == BundleEvent.RESOLVED) {
            register(event.getBundle());
        } else if (event.getType() == BundleEvent.UNRESOLVED || event.getType() == BundleEvent.UNINSTALLED) {

            final Bundle unregisteredBundle = event.getBundle();

            lock.writeLock().lock();
            try {
                factories.remove(unregisteredBundle.getBundleId());

                if (unregisteredBundle.getSymbolicName().equals(BundleSymbolicNAME)) {
                    bundleContext.removeBundleListener(this);
                    factories.clear();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Enumeration<URL> getPackageResources(final String packagePath, final ClassLoader classLoader) {
        List<URL> result = new LinkedList<URL>();
        classToBundleMapping.clear();

        for (Bundle bundle : bundleContext.getBundles()) {
            // Look for resources at the given <packagePath> and at WEB-INF/classes/<packagePath> in case a WAR is being examined.
            for (String bundlePackagePath : new String[] {packagePath, "WEB-INF/classes/" + packagePath}) {
                final Enumeration<URL> enumeration = (Enumeration<URL>) bundle.findEntries(bundlePackagePath, "*", false);

                if (enumeration != null) {
                    while (enumeration.hasMoreElements()) {
                        final URL url = enumeration.nextElement();
                        final String path = url.getPath();

                        final String className = (packagePath + path.substring(path.lastIndexOf('/'))).
                                replace('/', '.').replace(".class", "");

                        classToBundleMapping.put(className, bundle);
                        result.add(url);
                    }
                }
            }

            // Now interested only in .jar provided by current bundle.
            final Enumeration<URL> jars = bundle.findEntries("/", "*.jar", true);
            if (jars != null) {
                while (jars.hasMoreElements()) {
                    try {
                        final InputStream inputStream = classLoader.getResourceAsStream(jars.nextElement().getPath());
                        final JarInputStream jarInputStream = new JarInputStream(inputStream);

                        JarEntry jarEntry;
                        while ((jarEntry = jarInputStream.getNextJarEntry()) != null) {
                            final String jarEntryName = jarEntry.getName();

                            if (jarEntryName.endsWith(".class") && jarEntryName.contains(packagePath)) {
                                classToBundleMapping.put(jarEntryName.replace(".class", "").replace('/', '.'), bundle);
                                result.add(bundle.getResource(jarEntryName));
                            }
                        }
                    } catch (Exception e) {
                        // Ignore.
                    }
                }
            }
        }

        return Collections.enumeration(result);
    }

    /**
     * Get the Class from the class name.
     * <p>
     * The context class loader will be utilized if accessible and non-null.
     * Otherwise the defining class loader of this class will
     * be utilized.
     *
     * @param className the class name.
     * @return the Class, otherwise null if the class cannot be found.
     * @throws ClassNotFoundException if the class cannot be found.
     */
    public Class<?> classForNameWithException(final String className) throws ClassNotFoundException {
        final Bundle bundle = classToBundleMapping.get(className);

        if (bundle == null) {
            throw new ClassNotFoundException(className);
        }
        return bundle.loadClass(className);
    }

    /**
     * Tries to load resource bundle via OSGi means. No caching involved here,
     * as localization properties are being cached in Localizer class already.
     *
     * @param bundleName name of the resource bundle to load
     * @return resource bundle instance if found, null otherwise
     */
    public ResourceBundle getResourceBundle(final String bundleName) {
        final int lastDotIndex = bundleName.lastIndexOf('.');
        final String path = bundleName.substring(0, lastDotIndex).replace('.', '/');
        final String propertiesName = bundleName.substring(lastDotIndex + 1, bundleName.length()) + ".properties";
        for (Bundle bundle : bundleContext.getBundles()) {
            final Enumeration entries = bundle.findEntries(path, propertiesName, false);
            if (entries != null && entries.hasMoreElements()) {
                final URL entryUrl = (URL)entries.nextElement();
                try {
                    return new PropertyResourceBundle(entryUrl.openStream());
                } catch (IOException ex) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        // does not make sense to localize this
                        LOGGER.fine("Exception caught when tried to load resource bundle in OSGi");
                    }
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Creates a new OsgiRegistry instance bound to a particular OSGi runtime.
     * The only parameter must be an instance of a {@link BundleContext}.
     *
     * @param bundleContext must be a non-null instance of a BundleContext
     */
    private OsgiRegistry(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    /**
     * Will hook up this instance with the OSGi runtime.
     * This is to actually update SPI provider lookup and class loading mechanisms in Jersey
     * to utilize OSGi features.
     */
    void hookUp() {
        bundleContext.addBundleListener(this);
        registerExistingBundles();
    }

    private void registerExistingBundles() {
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.STARTING
                    || bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STOPPING) {
                register(bundle);
            }
        }
    }

    private void register(final Bundle bundle) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "checking bundle {0}", bundle.getBundleId());
        }

        Map<String, Callable<List<Class<?>>>> map;
        lock.writeLock().lock();
        try {
            map = factories.get(bundle.getBundleId());
            if (map == null) {
                map = new ConcurrentHashMap<String, Callable<List<Class<?>>>>();
                factories.put(bundle.getBundleId(), map);
            }
        } finally {
            lock.writeLock().unlock();
        }

        Enumeration e = bundle.findEntries("META-INF/services/", "*", false);
        if (e != null) {
            while (e.hasMoreElements()) {
                final URL u = (URL) e.nextElement();
                final String url = u.toString();
                if (url.endsWith("/")) {
                    continue;
                }
                final String factoryId = url.substring(url.lastIndexOf("/") + 1);
                map.put(factoryId, new BundleSpiProvidersLoader(factoryId, u, bundle));
            }
        }
    }
}