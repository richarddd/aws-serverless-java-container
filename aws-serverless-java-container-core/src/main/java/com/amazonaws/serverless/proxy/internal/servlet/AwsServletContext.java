/*
 * Copyright 2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.serverless.proxy.internal.servlet;


import com.amazonaws.serverless.proxy.internal.LambdaContainerHandler;
import com.amazonaws.serverless.proxy.internal.SecurityUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.*;
import jakarta.servlet.ServletContext;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.*;


/**
 * Basic implementation of the <code>ServletContext</code> object. Because this library is not a complete container
 * implementation the majority of methods in this object return a NotImplementedException or null. Supported properties
 * are <code>initParameters</code>, <code>attributes</code>, and <code>filters</code>.
 */
public class AwsServletContext
        implements ServletContext {

    //-------------------------------------------------------------
    // Constants - Public
    // -------------------------------------------------------------
    public static final int SERVLET_API_MAJOR_VERSION = 6;
    public static final int SERVLET_API_MINOR_VERSION = 0;
    public static final String SERVER_INFO = LambdaContainerHandler.SERVER_INFO + "/" + SERVLET_API_MAJOR_VERSION + "." + SERVLET_API_MINOR_VERSION;


    //-------------------------------------------------------------
    // Variables - Private
    //-------------------------------------------------------------
    private Map<String, FilterHolder> filters;
    private Map<String, AwsServletRegistration> servletRegistrations;
    private Map<String, Object> attributes;
    private Map<String, String> initParameters;
    private AwsLambdaServletContainerHandler containerHandler;
    private Logger log = LoggerFactory.getLogger(AwsServletContext.class);


    //-------------------------------------------------------------
    // Variables - Private - Static
    //-------------------------------------------------------------
    private static AwsServletContext instance;


    //-------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------

    public AwsServletContext(AwsLambdaServletContainerHandler containerHandler) {
        this.containerHandler = containerHandler;
        this.attributes = new HashMap<>();
        this.initParameters = new HashMap<>();
        this.filters = new LinkedHashMap<>();
        this.servletRegistrations = new HashMap<>();
    }

    //-------------------------------------------------------------
    // Implementation - ServletContext
    //-------------------------------------------------------------

    public static void clearServletContextCache() {
        instance = null;
    }


    @Override
    public String getContextPath() {
        // servlets are always at the root.
        return "";
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getResponseCharacterEncoding() {
        return null;
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestCharacterEncoding() {
        return null;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSessionTimeout() {
        return 0;
    }


    @Override
    public ServletContext getContext(String s) {
        // all urls point to the same context.
        return this;
    }


    @Override
    public int getMajorVersion() {
        return SERVLET_API_MAJOR_VERSION;
    }


    @Override
    public int getMinorVersion() {
        return SERVLET_API_MINOR_VERSION;
    }


    @Override
    public int getEffectiveMajorVersion() {
        return SERVLET_API_MAJOR_VERSION;
    }


    @Override
    public int getEffectiveMinorVersion() {
        return SERVLET_API_MINOR_VERSION;
    }


    @Override
    @SuppressFBWarnings("PATH_TRAVERSAL_IN") // suppressing because we are using the getValidFilePath
    public String getMimeType(String file) {
        if (file == null || !file.contains(".")) {
            return null;
        }

        String mimeType = null;

        // may not work on Lambda until mailcap package is present https://github.com/awslabs/aws-serverless-java-container/pull/504
        try {
            mimeType = Files.probeContentType(Paths.get(file));
        } catch (IOException | InvalidPathException e) {
            log("unable to probe for content type, will use fallback", e);
        }

        if (mimeType == null) {
            try {
                String mimeTypeGuess = URLConnection.guessContentTypeFromName(new File(file).getName());
                if (mimeTypeGuess !=null) {
                    mimeType = mimeTypeGuess;
                }
            } catch (Exception e) {
                log("couldn't find a better contentType than " + mimeType + " for file " + file, e);
            }
        }

        return mimeType;
    }


    @Override
    public Set<String> getResourcePaths(String s) {
        // We do not know the resources from here, we'd need to get the list from the frameworks.
        // TODO: Perhaps declare a new reader interface that can be implemented in framework-specific modules
        throw new UnsupportedOperationException();
    }


    @Override
    public URL getResource(String s) throws MalformedURLException {
        return AwsServletContext.class.getResource(s);
    }


    @Override
    public InputStream getResourceAsStream(String s) {
        return AwsServletContext.class.getResourceAsStream(s);
    }


    @Override
    public RequestDispatcher getRequestDispatcher(String s) {
        return new AwsProxyRequestDispatcher(s, false, containerHandler);
    }


    @Override
    public RequestDispatcher getNamedDispatcher(String s) {
        return new AwsProxyRequestDispatcher(s, true, containerHandler);
    }

    public Servlet getServletForPath(String path) {
        String[] pathParts = path.split("/");
        for (AwsServletRegistration reg : servletRegistrations.values()) {
            for (String p : reg.getMappings()) {
                if ("".equals(p) || "/".equals(p) || "/*".equals(p)) {
                    return reg.getServlet();
                }
                // if  I have no path and I haven't matched something now I'll just move on to the next
                if ("".equals(path) || "/".equals(path)) {
                    continue;
                }
                String[] regParts = p.split("/");
                for (int i = 0; i < regParts.length; i++) {
                    if (!regParts[i].equals(pathParts[i]) && !"*".equals(regParts[i])) {
                        break;
                    }
                    if (i == regParts.length - 1 && (regParts[i].equals(pathParts[i]) || "*".equals(regParts[i]))) {
                        return reg.getServlet();
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void log(String s) {
        log.info(SecurityUtils.encode(s));
    }


    @Override
    public void log(String s, Throwable throwable) {
        log.error(SecurityUtils.encode(s), throwable);
    }


    @Override
    public String getRealPath(String s) {
        String absPath = null;
        URL fileUrl = ClassLoader.getSystemResource(s);
        if (fileUrl != null) {
            try {
                absPath = new File(fileUrl.toURI()).getAbsolutePath();
            } catch (URISyntaxException e) {
                log.error("Error while looking for real path {}: {}", SecurityUtils.encode(s), SecurityUtils.encode(e.getMessage()));
            }
        }
        return absPath;
    }


    @Override
    public String getServerInfo() {
        return SERVER_INFO;
    }


    @Override
    public String getInitParameter(String s) {
        return initParameters.get(s);
    }


    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }


    @Override
    public boolean setInitParameter(String s, String s1) {
        initParameters.put(s, s1);
        return true;
    }


    @Override
    public Object getAttribute(String s) {
        return attributes.get(s);
    }


    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }


    @Override
    public void setAttribute(String s, Object o) {
        attributes.put(s, o);
    }


    @Override
    public void removeAttribute(String s) {
        attributes.remove(s);
    }


    @Override
    public String getServletContextName() {
        return null;
    }


    @Override
    public ServletRegistration.Dynamic addServlet(String s, String s1) {
        try {
            Class<? extends Servlet> servletClass = (Class<? extends Servlet>) this.getClassLoader().loadClass(s1);
            Servlet servlet = createServlet(servletClass);
            servletRegistrations.put(s, new AwsServletRegistration(s, servlet, this));
            return servletRegistrations.get(s);
        } catch (ServletException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public ServletRegistration.Dynamic addServlet(String s, Servlet servlet) {
        servletRegistrations.put(s, new AwsServletRegistration(s, servlet, this));
        return servletRegistrations.get(s);
    }


    @Override
    public ServletRegistration.Dynamic addServlet(String s, Class<? extends Servlet> aClass) {
        try {
            Servlet servlet = createServlet(aClass);
            servletRegistrations.put(s, new AwsServletRegistration(s, servlet, this));
            return servletRegistrations.get(s);
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String s, String s1) {
        throw new UnsupportedOperationException();
    }


    @Override
    public <T extends Servlet> T createServlet(Class<T> aClass) throws ServletException {
        /*log("Called createServlet: " + aClass.getName());
        log("Implemented frameworks are responsible for creating servlets");*/
        // TODO: This method introspects the given clazz for the following annotations: ServletSecurity, MultipartConfig,
        //  javax.annotation.security.RunAs, and javax.annotation.security.DeclareRoles. In addition, this method supports
        //  resource injection if the given clazz represents a Managed Bean. See the Java EE platform and JSR 299 specifications
        //  for additional details about Managed Beans and resource injection.
        try {
            return aClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new ServletException(e);
        }
    }


    @Override
    public ServletRegistration getServletRegistration(String s) {
        return servletRegistrations.get(s);
    }


    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return servletRegistrations;
    }


    @Override
    public FilterRegistration.Dynamic addFilter(String name, String filterClass) {
        try {
            Class<?> newFilterClass = getClassLoader().loadClass(filterClass);
            if (!Filter.class.isAssignableFrom(newFilterClass)) {
                throw new IllegalArgumentException(filterClass + " does not implement Filter");
            }
            @SuppressWarnings("unchecked")
            Class<? extends Filter> filterCastClass = (Class<? extends Filter>)newFilterClass;

            return addFilter(name, filterCastClass);
        } catch (ClassNotFoundException e) {
            log.error("Could not find filter class", e);
            throw new IllegalStateException("Filter class " + filterClass + " not found");
        }
    }


    @Override
    public FilterRegistration.Dynamic addFilter(String name, Filter filter) {
        if (name == null || "".equals(name.trim()))
            throw new IllegalArgumentException("Missing filter name");

        // filter already exists, we do nothing
        if (filters.containsKey(name)) {
            return null;
        } else {
            log.debug("Adding filter '{}' from {}", SecurityUtils.encode(name), SecurityUtils.encode(filter.toString()));
        }

        FilterHolder newFilter = new FilterHolder(name, filter, this);

        filters.put(newFilter.getFilterName(), newFilter);
        return newFilter.getRegistration();
    }


    @Override
    public FilterRegistration.Dynamic addFilter(String name, Class<? extends Filter> filterClass) {
        try {
            log.debug("Adding filter '{}' from {}", SecurityUtils.encode(name), SecurityUtils.encode(filterClass.getName()));
            Filter newFilter = createFilter(filterClass);
            return addFilter(name, newFilter);
        } catch (ServletException e) {
            // TODO: There is no clear indication in the servlet specs on whether we should throw an exception here.
            // See JavaDoc here: http://docs.oracle.com/javaee/7/api/javax/servlet/ServletContext.html#addFilter-java.lang.String-java.lang.Class-
            log.error("Could not register filter: ", e);
        }
        return null;
    }


    @Override
    public <T extends Filter> T createFilter(Class<T> aClass) throws ServletException {
        try {
            return aClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            log.error("Could not initialize filter class " + aClass.getName(), e);
            throw new ServletException();
        }
    }


    @Override
    public FilterRegistration getFilterRegistration(String s) {

        if (!filters.containsKey(s)) {
            return null;
        }
        return filters.get(s).getRegistration();
    }


    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        Map<String, FilterRegistration> registrations = new LinkedHashMap<>();
        for (Map.Entry<String, FilterHolder> entry : filters.entrySet()) {
            registrations.put(entry.getKey(), entry.getValue().getRegistration());
        }

        return registrations;
    }


    Map<String, FilterHolder> getFilterHolders() {
        return filters;
    }


    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return null;
    }


    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> set) {

    }


    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return null;
    }


    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return null;
    }


    @Override
    public void addListener(String s) {

    }


    @Override
    public <T extends EventListener> void addListener(T t) {

    }


    @Override
    public void addListener(Class<? extends EventListener> aClass) {

    }


    @Override
    public <T extends EventListener> T createListener(Class<T> aClass) throws ServletException {
        return null;
    }


    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }


    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }


    @Override
    public void declareRoles(String... strings) {

    }


    @Override
    public String getVirtualServerName() {
        return null;
    }
}