/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 - 2012 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.websocket.platform;

import org.glassfish.websocket.spi.SPIRegisteredEndpoint;
import org.glassfish.websocket.spi.SPIWebSocketProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 *
 * @author dannycoward
 */

public class BeanServer {
    final static Logger logger = Logger.getLogger("wsplatform");
    Set<SPIRegisteredEndpoint> endpoints = Collections.newSetFromMap(new ConcurrentHashMap<SPIRegisteredEndpoint, Boolean>());
    private ServerContainerImpl containerContext;
    private SPIWebSocketProvider engine;

    public BeanServer(String engineProviderClassname) {
        try {
            Class engineProviderClazz = Class.forName(engineProviderClassname);
            this.setEngine((SPIWebSocketProvider) engineProviderClazz.newInstance());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load provider class: " + engineProviderClassname + ". The provider class defaults to"
                    + "the grizzly provider. If you wish to provide your own implementation of the provider SPI, you can configure"
                    + "the provider class in the web.xml of the application using a"
                    + "context initialization parameter with key org.glassfish.websocket.provider.class, and using the full classname as the value.");
        }
        logger.info("Provider class loaded: " + engineProviderClassname);
    }


    public BeanServer(SPIWebSocketProvider engine) {
        this.setEngine(engine);
    }


    private void setEngine(SPIWebSocketProvider engine) {
        this.engine = engine;
        logger.info("Provider class instance: " + engine + " of class " + this.engine.getClass() + " assigned in the BeanServer");
    }

    public ServerContainerImpl getContainerContext() {
        return containerContext;
    }

    public void closeWebSocketServer() {
        for (SPIRegisteredEndpoint wsa : this.endpoints) {
            wsa.remove();
            this.engine.unregister(wsa);
            logger.info("Closing down : " + wsa);
        }
    }

    public void initWebSocketServer(String wsPath, int port, Set<Class<?>> fqWSBeanNames) throws Exception {
        this.containerContext = new ServerContainerImpl(this, wsPath, port);
        for (Class webSocketApplicationBeanClazz : fqWSBeanNames) {
            this.containerContext.setApplicationLevelClassLoader(webSocketApplicationBeanClazz.getClassLoader());

            // introspect the bean and find all the paths....
            Map methodPathMap = this.getMethodToPathMap(webSocketApplicationBeanClazz);
            if (methodPathMap.isEmpty()) {
                logger.warning(webSocketApplicationBeanClazz + " has no path mappings");
            }

            Set<String> allPathsForBean = new HashSet(methodPathMap.values());

            // create one adapter per path. So each class may have multiple adapters.
            for (String nextPath : allPathsForBean) {
                Model model = new Model(webSocketApplicationBeanClazz);
                String wrapperBeanPath = wsPath + nextPath;
                WebSocketEndpointImpl webSocketEndpoint = new WebSocketEndpointImpl(this.containerContext);
                webSocketEndpoint.doInit(wrapperBeanPath,model);
                this.deploy(webSocketEndpoint);
            }
        }
    }

    void deploy(WebSocketEndpointImpl wsa) {
        SPIRegisteredEndpoint ge  = this.engine.register(wsa);
        this.endpoints.add(ge);
        logger.info("Registered a " + wsa.getClass() + " at " + wsa.getPath());
    }

    private Set getMethodsForPath(Class beanClazz, String path) throws Exception {
        Set<Method> s = new HashSet<Method>();
        Map<Method, String> methodPath = this.getMethodToPathMap(beanClazz);
        for (Method m : methodPath.keySet()) {
            String p = methodPath.get(m);
            if (p.equals(path)) {
                s.add(m);
            }
        }
        return s;
    }

    private Map<Method, String> getMethodToPathMap(Class beanClazz) throws Exception {
        Map<Method, String> pathMappings = new HashMap();
        Method[] methods = beanClazz.getDeclaredMethods();
        for (Method method : methods) {
            org.glassfish.websocket.api.annotations.WebSocketEndpoint wsClass = (org.glassfish.websocket.api.annotations.WebSocketEndpoint) beanClazz.getAnnotation(org.glassfish.websocket.api.annotations.WebSocketEndpoint.class);
            pathMappings.put(method, wsClass.path());
        }
        return pathMappings;
    }
}
