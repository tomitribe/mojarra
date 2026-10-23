/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.faces.facelets.impl;

import static com.sun.faces.config.WebConfiguration.BooleanWebContextInitParameter.UseFaceletsID;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.sun.faces.RIConstants;
import com.sun.faces.config.WebConfiguration;
import com.sun.faces.context.FacesFileNotFoundException;
import com.sun.faces.facelets.compiler.Compiler;
import com.sun.faces.util.Cache;
import com.sun.faces.util.FacesLogger;
import com.sun.faces.util.Util;

import jakarta.el.ELException;
import jakarta.faces.FacesException;
import jakarta.faces.FactoryFinder;
import jakarta.faces.application.Application;
import jakarta.faces.component.UIComponent;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.facelets.Facelet;
import jakarta.faces.view.facelets.FaceletCache;
import jakarta.faces.view.facelets.FaceletCacheFactory;
import jakarta.faces.view.facelets.FaceletException;
import jakarta.faces.view.facelets.FaceletHandler;
import jakarta.faces.view.facelets.ResourceResolver;

/**
 * Default FaceletFactory implementation.
 *
 * @author Jacob Hookom
 * @version $Id: DefaultFaceletFactory.java,v 1.10 2007/04/09 01:13:17 youngm Exp $
 */
public class DefaultFaceletFactory {

    protected final static Logger log = FacesLogger.FACELETS_FACTORY.getLogger();

    private static final String ARCHIVE_PROTOCOL = "jar";
    private static final String ARCHIVE_SEPARATOR = "!/";
    private static final String RESOURCE_LIBRARY_DIR = "/META-INF/resources/";
    private static final String CONTRACTS_DIR = "/" + WebConfiguration.META_INF_CONTRACTS_DIR + "/";
    private static final String FLOWS_DIR = "/" + RIConstants.FLOW_IN_JAR_PREFIX + "/";
    private static final String RELATIVE_PATH_REQUIRED = " must be a relative path within the application";

    private Compiler compiler;

    // We continue to use a ResourceResolver just in case someone
    // provides a custom one. The DefaultResourceResolver simply uses
    // the ResourceHandler to do its work.
    private ResourceResolver resolver;

    private URL baseUrl;

    private String baseUrlAsString;

    private String[] faceletResourceSuffixes;

    private long refreshPeriod;

    private FaceletCache<DefaultFacelet> cache;

    private ConcurrentMap<String, FaceletCache<DefaultFacelet>> cachePerContract;

    Cache<String, IdMapper> idMappers;

    // ------------------------------------------------------------ Constructors

    public DefaultFaceletFactory() {
        compiler = null;
        resolver = null;
        refreshPeriod = -1;
        cache = null;
        baseUrl = null;
    }

    public DefaultFaceletFactory(Compiler compiler, ResourceResolver resolver) throws IOException {

        this(compiler, resolver, -1, null);

    }

    public DefaultFaceletFactory(Compiler compiler, ResourceResolver resolver, long refreshPeriod) {
        this(compiler, resolver, refreshPeriod, null);
    }

    public DefaultFaceletFactory(Compiler compiler, ResourceResolver resolver, long refreshPeriod, FaceletCache cache) {
        init(compiler, resolver, refreshPeriod, cache);
    }

    public final void init(Compiler compiler, ResourceResolver resolver, long refreshPeriod, FaceletCache cache) {
        Util.notNull("compiler", compiler);
        Util.notNull("resolver", resolver);

        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null) {
            throw new IllegalStateException("DefaultFaceletFactory cannot locate the faces context");
        }
        ExternalContext externalContext = facesContext.getExternalContext();
        WebConfiguration config = WebConfiguration.getInstance(externalContext);

        this.compiler = compiler;
        cachePerContract = new ConcurrentHashMap<>();
        initResourceResolution(resolver, config.getFaceletResourceSuffixes());
        this.idMappers = config.isOptionEnabled(UseFaceletsID) ? null : new Cache<>(new IdMapperFactory());
        // this.location = url;
        refreshPeriod = refreshPeriod >= 0 ? refreshPeriod * 1000 : -1;
        this.refreshPeriod = refreshPeriod;
        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Using ResourceResolver: {0}", resolver);
            log.log(Level.FINE, "Using Refresh Period: {0}", refreshPeriod);
        }

        // We can cast to the FaceletCache<DefaultFacelet> here because we know
        // that the Generics information is only used at compile time, and all cache
        // implementations will be using instance factories provided by us and returning DefaultFacelet
        this.cache = initCache(cache);
    }

    /**
     * Installs everything {@link #resolveURL(URL, String)} needs to resolve a path and to decide whether the result is
     * a Facelet of this application.
     *
     * @param resolver the resolver to look webapp relative paths up with.
     * @param faceletResourceSuffixes the suffixes a Facelet resource may have.
     */
    void initResourceResolution(ResourceResolver resolver, String[] faceletResourceSuffixes) {
        this.resolver = resolver;
        this.faceletResourceSuffixes = faceletResourceSuffixes;
        baseUrl = resolver.resolveUrl("/");
        baseUrlAsString = baseUrl.toExternalForm();

        if (!baseUrlAsString.endsWith("/")) {
            baseUrlAsString += "/";
        }
    }

    private FaceletCache<DefaultFacelet> initCache(FaceletCache<DefaultFacelet> cache) {

        if (cache == null) {
            FaceletCacheFactory cacheFactory = (FaceletCacheFactory) FactoryFinder.getFactory(FactoryFinder.FACELET_CACHE_FACTORY);
            cache = cacheFactory.getFaceletCache();
        }

        // Create instance factories for the cache, so that the cache can
        // create Facelets and Metadata Facelets
        FaceletCache.MemberFactory<DefaultFacelet> faceletFactory = key -> createFacelet(key);
        FaceletCache.MemberFactory<DefaultFacelet> metadataFaceletFactory = key -> createMetadataFacelet(key);

        cache.setCacheFactories(faceletFactory, metadataFaceletFactory);
        return cache;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.facelets.FaceletFactory#getResourceResolver
     */
    public ResourceResolver getResourceResolver() {
        return resolver;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.facelets.FaceletFactory#getFacelet(java.lang.String)
     */
    public Facelet getFacelet(FacesContext context, String uri) throws IOException {

        return this.getFacelet(context, resolveURL(uri));

    }

    public Facelet getMetadataFacelet(FacesContext context, String uri) throws IOException {

        return this.getMetadataFacelet(context, resolveURL(uri));

    }

    /**
     * Resolves a path based on the passed URL. If the path starts with '/', then resolve the path against
     * {@link jakarta.faces.context.ExternalContext#getResource(java.lang.String)
     * jakarta.faces.context.ExternalContext#getResource(java.lang.String)}. Otherwise create a new URL via
     * {@link URL#URL(java.net.URL, java.lang.String) URL(URL, String)}.
     *
     * @param source base to resolve from
     * @param path relative path to the source
     *
     * @return resolved URL
     *
     * @throws IOException
     */
    public URL resolveURL(URL source, String path) throws IOException {
        requirePlainRelativePath(path);

        // PENDING(FCAPUTO): always go to the resolver to make resource libary contracts work with relative urls
        if (path.startsWith("/")) {
            URL url = resolver.resolveUrl(path);
            if (url == null) {
                throw new FacesFileNotFoundException(path + " Not Found in ExternalContext as a Resource");
            }
            // A top-level view is already constrained by the ViewHandler/container; an include or
            // template (resolved against a nested facelet as source) must point at a Facelet resource,
            // so a user-controlled src cannot disclose descriptors such as /WEB-INF/web.xml.
            if (source != baseUrl) {
                requireFaceletResource(url, path);
            }
            return url;
        }

        // A relative src/template must resolve to a Facelet inside this application. Reject absolute
        // URLs (http:, file:, jar:, ...) whose scheme discards the base, "../" traversal that escapes
        // the deployment, and any non-Facelet resource.
        URL url = new URL(source, path);
        requireSameOrigin(source, url, path);
        requireWithinApplicationRoot(source, url, path);
        requireFaceletResource(url, path);
        return url;
    }

    /**
     * Rejects a path that is not plain: one holding a percent sign or a backslash. A path is authored in the facelet,
     * so it is already decoded and slash delimited; either character is one a downstream decoder or a file system turns
     * into a separator after {@link URL#URL(URL, String)} kept it literal and the containment check accepted it, which
     * lets it traverse when the resource is read. A path holding either character is therefore unsupported, even where
     * the character legitimately appears in a resource name.
     *
     * @param path path as declared by the Facelet.
     * @throws FacesFileNotFoundException if the path holds a percent sign or a backslash.
     */
    private static void requirePlainRelativePath(String path) throws FacesFileNotFoundException {
        if (path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) {
            throw new FacesFileNotFoundException(path + RELATIVE_PATH_REQUIRED);
        }
    }

    private void requireSameOrigin(URL source, URL url, String path) throws FacesFileNotFoundException {
        if (!url.getProtocol().equals(source.getProtocol()) || !Objects.equals(getOrigin(url), getOrigin(source))) {
            throw new FacesFileNotFoundException(path + RELATIVE_PATH_REQUIRED);
        }
    }

    /**
     * A container serves its Facelets from a union of resource roots: the webapp itself, any further resource base it
     * was configured with, and every {@code META-INF/resources} on the classpath. A resolved url is within the
     * application when it lands in the webapp, or in the resource root of the Facelet declaring the path, so that a
     * Facelet which a resource library contributes may resolve a relative path within that library whether the library
     * is deployed as an archive or exploded on disk.
     *
     * @param source url of the Facelet declaring the path.
     * @param url url the path resolved to.
     * @param path path as declared by that Facelet.
     * @throws FacesFileNotFoundException if the resolved url lies in neither.
     */
    private void requireWithinApplicationRoot(URL source, URL url, String path) throws FacesFileNotFoundException {
        String urlAsString = url.toExternalForm();

        if (!urlAsString.startsWith(baseUrlAsString) && !urlAsString.startsWith(getResourceRoot(source.toExternalForm()))) {
            throw new FacesFileNotFoundException(path + " is not within the application root");
        }
    }

    /**
     * Returns the resource root the given Facelet comes out of: the resource library directory it lives under, else the
     * contract or flow directory it lives under, else its own directory. The root must be a pure function of the url.
     * Anything request scoped, such as the locale or the active resource library contracts, would let the request order
     * decide how far a relative path may reach.
     * <p>
     * The markers below are the complete set of directories this implementation serves Facelets out of. A further one
     * has to be added here, or Facelets under it are bounded by their own directory.
     *
     * @param source external form of the Facelet url to return the resource root of.
     * @return the resource root, as a url prefix ending in a slash.
     */
    private static String getResourceRoot(String source) {
        int library = source.lastIndexOf(RESOURCE_LIBRARY_DIR);

        if (library >= 0) {
            return source.substring(0, library + RESOURCE_LIBRARY_DIR.length());
        }

        String contract = getNamedRoot(source, CONTRACTS_DIR);

        if (contract != null) {
            return contract;
        }

        String flow = getNamedRoot(source, FLOWS_DIR);

        if (flow != null) {
            return flow;
        }

        int directory = source.lastIndexOf('/');

        return directory < 0 ? source + '/' : source.substring(0, directory + 1);
    }

    /**
     * Returns the root of a directory whose contents are one level of named subdirectories, such as the contracts and
     * the flows directory: the root is the named subdirectory the Facelet lives in, not the directory holding them all,
     * so that one contract or flow cannot reach into another.
     *
     * @param source external form of the Facelet url to return the resource root of.
     * @param marker the directory holding the named subdirectories, slash delimited on both ends.
     * @return the resource root, as a url prefix ending in a slash, or null if the Facelet does not live under one.
     */
    private static String getNamedRoot(String source, String marker) {
        int start = source.lastIndexOf(marker);

        if (start < 0) {
            return null;
        }

        int end = source.indexOf('/', start + marker.length());

        return end < 0 ? null : source.substring(0, end + 1);
    }

    /**
     * A nested scheme carries no authority: every {@code jar:} URL has a null one, so a remote archive would compare
     * equal to the local archive holding the facelet. The archive a resource lives in is therefore its origin.
     *
     * @return the origin to compare a resolved URL against the facelet it was resolved from.
     */
    private static String getOrigin(URL url) {
        if (!ARCHIVE_PROTOCOL.equals(url.getProtocol())) {
            return url.getAuthority();
        }

        String form = url.toExternalForm();
        int separator = form.indexOf(ARCHIVE_SEPARATOR);

        return separator == -1 ? form : form.substring(0, separator + ARCHIVE_SEPARATOR.length());
    }

    private void requireFaceletResource(URL url, String path) throws FacesFileNotFoundException {
        String resourcePath = url.getPath();
        for (String suffix : faceletResourceSuffixes) {
            if (resourcePath.endsWith(suffix)) {
                return;
            }
        }
        throw new FacesFileNotFoundException(path + " is not a Facelet resource");
    }

    /**
     * Create a Facelet from the passed URL. This method checks if the cached Facelet needs to be refreshed before
     * returning. If so, uses the passed URL to build a new instance;
     *
     * @param url source url
     *
     * @return Facelet instance
     *
     * @throws IOException
     * @throws FaceletException
     * @throws FacesException
     * @throws ELException
     */
    public Facelet getFacelet(FacesContext context, URL url) throws IOException {

        Facelet result = getCache(context).getFacelet(url);

        DefaultFacelet _facelet = null;
        if (result instanceof DefaultFacelet) {
            _facelet = (DefaultFacelet) result;
            String docType = _facelet.getSavedDoctype();
            if (null != docType) {
                Util.saveDOCTYPEToFacesContextAttributes(docType);
            }

            String xmlDecl = _facelet.getSavedXMLDecl();
            if (null != xmlDecl) {
                Util.saveXMLDECLToFacesContextAttributes(xmlDecl);
            }
        }

        return result;

    }

    public Facelet getMetadataFacelet(FacesContext context, URL url) throws IOException {
        return getCache(context).getViewMetadataFacelet(url);
    }

    public boolean needsToBeRefreshed(URL url) {
        if (!cache.isFaceletCached(url)) {
            return true;
        }
        if (cachePerContract == null) {
            return false;
        }
        // PENDING(FCAPUTO) not sure, if this is what we want.
        for (FaceletCache<DefaultFacelet> faceletCache : cachePerContract.values()) {
            if (!faceletCache.isFaceletCached(url)) {
                return true;
            }
        }
        return false;
    }

    private FaceletCache<DefaultFacelet> getCache(FacesContext context) {
        List<String> contracts = context.getResourceLibraryContracts();
        if (!contracts.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < contracts.size(); i++) {
                builder.append(contracts.get(i));
                if (i + 1 != contracts.size()) {
                    builder.append(",");
                }
            }
            String contractsKey = builder.toString();
            FaceletCache<DefaultFacelet> faceletCache = cachePerContract.get(contractsKey);
            if (faceletCache == null) {
                // PENDING(FCAPUTO) we don't support com.sun.faces.config.WebConfiguration.WebContextInitParameter#FaceletCache for
                // contracts
                faceletCache = initCache(null);
                cachePerContract.putIfAbsent(contractsKey, faceletCache);
                faceletCache = cachePerContract.get(contractsKey);
            }
            return faceletCache;
        }
        return cache;
    }

    private URL resolveURL(String uri) throws IOException {
        // PENDING(FCAPUTO) Deactivated caching for resource library contracts. If we still want to cache it, we need a cache
        // per contract libraries list.
        // But the ResourceHandler caches on his own (using ResourceManager).
        URL url = this.resolveURL(baseUrl, uri);
        if (url == null) {
            throw new IOException("'" + uri + "' not found.");
        }
        return url;
    }

    public UIComponent _createComponent(FacesContext context, String taglibURI, String tagName, Map<String, Object> attributes) {
        // PENDING(FCAPUTO) does this work for resource library contracts? I think so.
        UIComponent result = null;
        Application app = context.getApplication();
        ExternalContext extContext = context.getExternalContext();
        File tmpDir = (File) extContext.getApplicationMap().get("jakarta.servlet.context.tempdir");
        File tempFile = null;
        OutputStreamWriter osw = null;
        try {

            // create a temporary file in that directory
            tempFile = File.createTempFile("mojarra", ".tmp", tmpDir);
            osw = new OutputStreamWriter(new FileOutputStream(tempFile), RIConstants.CHAR_ENCODING);
            osw.append("<?xml version='1.0' encoding='");
            osw.append(RIConstants.CHAR_ENCODING);
            osw.append("' ?>");
            osw.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">");
            osw.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"\n");
            osw.append("      xmlns:j=\"").append(taglibURI).append("\">");
            osw.append("  <j:").append(tagName).append(" ");
            if (null != attributes && !attributes.isEmpty()) {
                for (Map.Entry<String, Object> attr : attributes.entrySet()) {
                    osw.append(attr.getKey()).append("=\"").append(attr.getValue().toString()).append("\"").append(" ");
                }
            }
            String tempId = context.getViewRoot().createUniqueId(context, tagName);
            osw.append(" id=\"").append(tempId).append("\" />");
            osw.append("</html>");
            try {
                osw.flush();
                osw.close();
            } catch (IOException ex) {
                if (log.isLoggable(Level.FINEST)) {
                    log.log(Level.FINEST, "Flushing and closing stream", ex);
                }
            }

            URL fabricatedFaceletPage = tempFile.toURI().toURL();
            Facelet f = createFacelet(fabricatedFaceletPage);
            UIComponent tmp = app.createComponent("jakarta.faces.NamingContainer");
            tmp.setId(context.getViewRoot().createUniqueId());
            f.apply(context, tmp);
            result = tmp.findComponent(tempId);
            tmp.getChildren().clear();
            osw = null;

        } catch (MalformedURLException mue) {
            if (log.isLoggable(Level.FINEST)) {
                log.log(Level.FINEST, "Invalid URL", mue);
            }
        } catch (IOException ioe) {
            if (log.isLoggable(Level.FINEST)) {
                log.log(Level.FINEST, "I/O error", ioe);
            }
        } finally {
            if (null != osw) {
                try {
                    osw.close();
                } catch (IOException ioe) {
                    if (log.isLoggable(Level.FINEST)) {
                        log.log(Level.FINEST, "Closing stream", ioe);
                    }
                }
            }
            if (null != tempFile) {
                boolean successful = tempFile.delete();
                if (!successful && log.isLoggable(Level.FINEST)) {
                    log.log(Level.FINEST, "Unable to delete temporary file.");
                }
            }
        }

        try {
            byte[] faceletPage = "facelet".getBytes(RIConstants.CHAR_ENCODING);
            ByteArrayInputStream bais = new ByteArrayInputStream(faceletPage);
        } catch (UnsupportedEncodingException uee) {
            if (log.isLoggable(Level.SEVERE)) {
                log.log(Level.SEVERE, "Unsupported encoding when creating component for " + tagName + " in " + taglibURI, uee);
            }
        }

        if (null != result) {
            result.setId(null);
        }
        return result;
    }

    /**
     * Uses the internal Compiler reference to build a Facelet given the passed URL.
     *
     * @param url source
     *
     * @return a Facelet instance
     *
     * @throws IOException
     * @throws FaceletException
     * @throws FacesException
     * @throws ELException
     */
    private DefaultFacelet createFacelet(URL url) throws IOException {
        String escapedBaseURL = Pattern.quote(this.baseUrl.getFile());
        String alias = '/' + url.getFile().replaceFirst(escapedBaseURL, "");
        return createFacelet(url, alias);
    }

    private DefaultFacelet createFacelet(URL url, String alias) throws IOException {
        if (log.isLoggable(Level.FINE)) {
            log.fine("Creating Facelet for: " + url);
        }
        try {
            FaceletHandler h = compiler.compile(url, alias);
            return new DefaultFacelet(this, compiler.createExpressionFactory(), url, alias, h);
        } catch (FileNotFoundException fnfe) {
            throw new FileNotFoundException("Facelet " + alias + " not found at: " + url.toExternalForm());
        }
    }

    private DefaultFacelet createMetadataFacelet(URL url) throws IOException {

        if (log.isLoggable(Level.FINE)) {
            log.fine("Creating Metadata Facelet for: " + url);
        }
        String escapedBaseURL = Pattern.quote(baseUrl.getFile());
        String alias = '/' + url.getFile().replaceFirst(escapedBaseURL, "");
        try {
            FaceletHandler h = compiler.metadataCompile(url, alias);
            return new DefaultFacelet(this, compiler.createExpressionFactory(), url, alias, h);
        } catch (FileNotFoundException fnfe) {
            throw new FileNotFoundException("Facelet " + alias + " not found at: " + url.toExternalForm());
        }

    }

    public long getRefreshPeriod() {
        return refreshPeriod;
    }

    // ---------------------------------------------------------- Nested Classes

    private static final class IdMapperFactory implements Cache.Factory<String, IdMapper> {

        // ------------------------------------------ Methods from Cache.Factory

        @Override
        public IdMapper newInstance(String arg) throws InterruptedException {

            return new IdMapper();

        }

    }

}
