/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.features.internal.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Conditional;
import org.apache.karaf.features.Dependency;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeatureEvent;
import org.apache.karaf.features.FeaturesListener;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.apache.karaf.features.RepositoryEvent;
import org.apache.karaf.features.internal.deployment.DeploymentBuilder;
import org.apache.karaf.features.internal.deployment.StreamProvider;
import org.apache.karaf.features.internal.resolver.FeatureNamespace;
import org.apache.karaf.features.internal.resolver.UriNamespace;
import org.apache.karaf.features.internal.util.ChecksumUtils;
import org.apache.karaf.features.internal.util.Macro;
import org.apache.karaf.features.internal.util.MultiException;
import org.apache.karaf.util.collections.CopyOnWriteArrayIdentityList;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.felix.resolver.Util.getSymbolicName;
import static org.apache.felix.resolver.Util.getVersion;

/**
 *
 */
public class FeaturesServiceImpl implements FeaturesService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeaturesServiceImpl.class);
    private static final String SNAPSHOT = "SNAPSHOT";
    private static final String MAVEN = "mvn:";

    private final Bundle bundle;
    private final BundleContext systemBundleContext;
    private final StateStorage storage;
    private final FeatureFinder featureFinder;
    private final EventAdminListener eventAdminListener;
    private final FeatureConfigInstaller configInstaller;
    private final String overrides;

    private final List<FeaturesListener> listeners = new CopyOnWriteArrayIdentityList<FeaturesListener>();

    // Synchronized on lock
    private final Object lock = new Object();
    private final State state = new State();
    private final Map<String, Repository> repositoryCache = new HashMap<String, Repository>();
    private Map<String, Map<String, Feature>> featureCache;



    public FeaturesServiceImpl(Bundle bundle,
                               BundleContext systemBundleContext,
                               StateStorage storage,
                               FeatureFinder featureFinder,
                               EventAdminListener eventAdminListener,
                               FeatureConfigInstaller configInstaller,
                               String overrides) {
        this.bundle = bundle;
        this.systemBundleContext = systemBundleContext;
        this.storage = storage;
        this.featureFinder = featureFinder;
        this.eventAdminListener = eventAdminListener;
        this.configInstaller = configInstaller;
        this.overrides = overrides;
        loadState();
    }

    //
    // State support
    //

    protected void loadState() {
        try {
            synchronized (lock) {
                storage.load(state);
            }
        } catch (IOException e) {
            LOGGER.warn("Error loading FeaturesService state", e);
        }
    }

    protected void saveState() {
        try {
            synchronized (lock) {
                storage.save(state);
            }
        } catch (IOException e) {
            LOGGER.warn("Error saving FeaturesService state", e);
        }
    }

    boolean isBootDone() {
        synchronized (lock) {
            return state.bootDone.get();
        }
    }

    void bootDone() {
        synchronized (lock) {
            state.bootDone.set(true);
            saveState();
        }
    }

    //
    // Listeners support
    //

    public void registerListener(FeaturesListener listener) {
        listeners.add(listener);
        try {
            Set<String> repositories = new TreeSet<String>();
            Set<String> installedFeatures = new TreeSet<String>();
            synchronized (lock) {
                repositories.addAll(state.repositories);
                installedFeatures.addAll(state.installedFeatures);
            }
            for (String uri : repositories) {
                Repository repository = new RepositoryImpl(URI.create(uri));
                listener.repositoryEvent(new RepositoryEvent(repository, RepositoryEvent.EventType.RepositoryAdded, true));
            }
            for (String id : installedFeatures) {
                Feature feature = org.apache.karaf.features.internal.model.Feature.valueOf(id);
                listener.featureEvent(new FeatureEvent(feature, FeatureEvent.EventType.FeatureInstalled, true));
            }
        } catch (Exception e) {
            LOGGER.error("Error notifying listener about the current state", e);
        }
    }

    public void unregisterListener(FeaturesListener listener) {
        listeners.remove(listener);
    }

    protected void callListeners(FeatureEvent event) {
        if (eventAdminListener != null) {
            eventAdminListener.featureEvent(event);
        }
        for (FeaturesListener listener : listeners) {
            listener.featureEvent(event);
        }
    }

    protected void callListeners(RepositoryEvent event) {
        if (eventAdminListener != null) {
            eventAdminListener.repositoryEvent(event);
        }
        for (FeaturesListener listener : listeners) {
            listener.repositoryEvent(event);
        }
    }

    //
    // Feature Finder support
    //

    @Override
    public URI getRepositoryUriFor(String name, String version) {
        return featureFinder.getUriFor(name, version);
    }

    @Override
    public String[] getRepositoryNames() {
        return featureFinder.getNames();
    }


    //
    // Repositories support
    //

    public Repository loadRepository(URI uri) throws Exception {
        // TODO: merge validation and loading by loading the DOM, validating, unmarshalling
        FeatureValidationUtil.validate(uri);
        RepositoryImpl repo = new RepositoryImpl(uri);
        repo.load();
        return repo;
    }

    @Override
    public void validateRepository(URI uri) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addRepository(URI uri) throws Exception {
        addRepository(uri, false);
    }

    @Override
    public void addRepository(URI uri, boolean install) throws Exception {
        if (install) {
            // TODO: implement
            throw new UnsupportedOperationException();
        }
        Repository repository = loadRepository(uri);
        synchronized (lock) {
            // Clean cache
            repositoryCache.put(uri.toString(), repository);
            featureCache = null;
            // Add repo
            if (!state.repositories.add(uri.toString())) {
                return;
            }
            saveState();
        }
        callListeners(new RepositoryEvent(repository, RepositoryEvent.EventType.RepositoryAdded, false));
    }

    @Override
    public void removeRepository(URI uri) throws Exception {
        removeRepository(uri, true);
    }

    @Override
    public void removeRepository(URI uri, boolean uninstall) throws Exception {
        // TODO: check we don't have any feature installed from this repository
        Repository repo;
        synchronized (lock) {
            // Remove repo
            if (!state.repositories.remove(uri.toString())) {
                return;
            }
            // Clean cache
            featureCache = null;
            repo = repositoryCache.get(uri.toString());
            List<String> toRemove = new ArrayList<String>();
            toRemove.add(uri.toString());
            while (!toRemove.isEmpty()) {
                Repository rep = repositoryCache.remove(toRemove.remove(0));
                if (rep != null) {
                    for (URI u : rep.getRepositories()) {
                        toRemove.add(u.toString());
                    }
                }
            }
            saveState();
        }
        if (repo == null) {
            repo = new RepositoryImpl(uri);
        }
        callListeners(new RepositoryEvent(repo, RepositoryEvent.EventType.RepositoryRemoved, false));
    }

    @Override
    public void restoreRepository(URI uri) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void refreshRepository(URI uri) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public Repository[] listRepositories() {
        // TODO: catching this exception is ugly: refactor the api
        try {
            getFeatures();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        synchronized (lock) {
            return repositoryCache.values().toArray(new Repository[repositoryCache.size()]);
        }
    }

    @Override
    public Repository getRepository(String name) {
        synchronized (lock) {
            for (Repository repo : this.repositoryCache.values()) {
                if (name.equals(repo.getName())) {
                    return repo;
                }
            }
            return null;
        }
    }

    //
    // Features support
    //

    public Feature getFeature(String name) throws Exception {
        return getFeature(name, null);
    }

    public Feature getFeature(String name, String version) throws Exception {
        Map<String, Feature> versions = getFeatures().get(name);
        return getFeatureMatching(versions, version);
    }

    protected Feature getFeatureMatching(Map<String, Feature> versions, String version) {
        if (version != null) {
            version = version.trim();
            if (version.equals(org.apache.karaf.features.internal.model.Feature.DEFAULT_VERSION)) {
                version = "";
            }
        } else {
            version = "";
        }
        if (versions == null || versions.isEmpty()) {
            return null;
        } else {
            Feature feature = version.isEmpty() ? null : versions.get(version);
            if (feature == null) {
                // Compute version range. If an version has been given, assume exact range
                VersionRange versionRange = version.isEmpty() ?
                        new VersionRange(Version.emptyVersion) :
                        new VersionRange(version, true, true);
                Version latest = Version.emptyVersion;
                for (String available : versions.keySet()) {
                    Version availableVersion = VersionTable.getVersion(available);
                    if (availableVersion.compareTo(latest) >= 0 && versionRange.contains(availableVersion)) {
                        feature = versions.get(available);
                        latest = availableVersion;
                    }
                }
            }
            return feature;
        }
    }

    public Feature[] listFeatures() throws Exception {
        Set<Feature> features = new HashSet<Feature>();
        for (Map<String, Feature> featureWithDifferentVersion : getFeatures().values()) {
            for (Feature f : featureWithDifferentVersion.values()) {
                features.add(f);
            }
        }
        return features.toArray(new Feature[features.size()]);
    }

    protected Map<String, Map<String, Feature>> getFeatures() throws Exception {
        List<String> uris;
        synchronized (lock) {
            if (featureCache != null) {
                return featureCache;
            }
            uris = new ArrayList<String>(state.repositories);
        }
        //the outer map's key is feature name, the inner map's key is feature version
        Map<String, Map<String, Feature>> map = new HashMap<String, Map<String, Feature>>();
        // Two phase load:
        // * first load dependent repositories
        List<String> toLoad = new ArrayList<String>(uris);
        while (!toLoad.isEmpty()) {
            String uri = toLoad.remove(0);
            Repository repo;
            synchronized (lock) {
                repo = repositoryCache.get(uri);
            }
            if (repo == null) {
                RepositoryImpl rep = new RepositoryImpl(URI.create(uri));
                rep.load();
                repo = rep;
                synchronized (lock) {
                    repositoryCache.put(uri, repo);
                }
            }
            for (URI u : repo.getRepositories()) {
                toLoad.add(u.toString());
            }
        }
        List<Repository> repos;
        synchronized (lock) {
            repos = new ArrayList<Repository>(repositoryCache.values());
        }
        // * then load all features
        for (Repository repo : repos) {
            for (Feature f : repo.getFeatures()) {
                if (map.get(f.getName()) == null) {
                    Map<String, Feature> versionMap = new HashMap<String, Feature>();
                    versionMap.put(f.getVersion(), f);
                    map.put(f.getName(), versionMap);
                } else {
                    map.get(f.getName()).put(f.getVersion(), f);
                }
            }
        }
        synchronized (lock) {
            if (uris.size() == state.repositories.size() &&
                    state.repositories.containsAll(uris)) {
                featureCache = map;
            }
        }
        return map;
    }

    //
    // Installed features
    //

    @Override
    public Feature[] listInstalledFeatures() throws Exception {
        Set<Feature> features = new HashSet<Feature>();
        Map<String, Map<String, Feature>> allFeatures = getFeatures();
        synchronized (lock) {
            for (Map<String, Feature> featureWithDifferentVersion : allFeatures.values()) {
                for (Feature f : featureWithDifferentVersion.values()) {
                    if (isInstalled(f)) {
                        features.add(f);
                    }
                }
            }
        }
        return features.toArray(new Feature[features.size()]);
    }

    @Override
    public boolean isInstalled(Feature f) {
        String id = normalize(f.getId());
        synchronized (lock) {
            return state.installedFeatures.contains(id);
        }
    }

    //
    // Installation and uninstallation of features
    //

    public void installFeature(String name) throws Exception {
        installFeature(name, EnumSet.noneOf(Option.class));
    }

    public void installFeature(String name, String version) throws Exception {
        installFeature(version != null ? name + "/" + version : name, EnumSet.noneOf(Option.class));
    }

    public void installFeature(String name, EnumSet<Option> options) throws Exception {
        doAddFeatures(Collections.singleton(name), options);
    }

    public void installFeature(String name, String version, EnumSet<Option> options) throws Exception {
        installFeature(version != null ? name + "/" + version : name, options);
    }

    public void installFeature(Feature feature, EnumSet<Option> options) throws Exception {
        installFeature(feature.getId());
    }

    public void installFeatures(Set<Feature> features, EnumSet<Option> options) throws Exception {
        Set<String> fs = new HashSet<String>();
        for (Feature f : features) {
            fs.add(f.getId());
        }
        doAddFeatures(fs, options);
    }

    @Override
    public void uninstallFeature(String name, String version) throws Exception {
        uninstallFeature(version != null ? name + "/" + version : name);
    }

    @Override
    public void uninstallFeature(String name, String version, EnumSet<Option> options) throws Exception {
        uninstallFeature(version != null ? name + "/" + version : name, options);
    }

    @Override
    public void uninstallFeature(String name) throws Exception {
        uninstallFeature(name, EnumSet.noneOf(Option.class));
    }

    @Override
    public void uninstallFeature(String name, EnumSet<Option> options) throws Exception {
        doRemoveFeatures(Collections.singleton(name), options);
    }


    //
    //
    //
    //   RESOLUTION
    //
    //
    //






    public void doAddFeatures(Set<String> features, EnumSet<Option> options) throws Exception {
        Set<String> required;
        Set<Long> managed;
        synchronized (lock) {
            required = new HashSet<String>(state.features);
            managed = new HashSet<Long>(state.managedBundles);
        }
        List<String> featuresToAdd = new ArrayList<String>();
        Map<String, Map<String, Feature>> featuresMap = getFeatures();
        for (String feature : features) {
            feature = normalize(feature);
            String name = feature.substring(0, feature.indexOf("/"));
            String version = feature.substring(feature.indexOf("/") + 1);
            Feature f = getFeatureMatching(featuresMap.get(name), version);
            if (f == null) {
                throw new IllegalArgumentException("No matching features for " + feature);
            }
            featuresToAdd.add(normalize(f.getId()));
        }
        featuresToAdd = new ArrayList<String>(new LinkedHashSet<String>(featuresToAdd));
        StringBuilder sb = new StringBuilder();
        sb.append("Adding features: ");
        for (int i = 0; i < featuresToAdd.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(featuresToAdd.get(i));
        }
        print(sb.toString(), options.contains(Option.Verbose));
        required.addAll(featuresToAdd);
        doInstallFeaturesInThread(required, managed, options);
    }

    public void doRemoveFeatures(Set<String> features, EnumSet<Option> options) throws Exception {
        Set<String> required;
        Set<Long> managed;
        synchronized (lock) {
            required = new HashSet<String>(state.features);
            managed = new HashSet<Long>(state.managedBundles);
        }
        List<String> featuresToRemove = new ArrayList<String>();
        for (String feature : new HashSet<String>(features)) {
            List<String> toRemove = new ArrayList<String>();
            feature = normalize(feature);
            if (feature.endsWith("/0.0.0")) {
                String nameSep = feature.substring(0, feature.indexOf("/") + 1);
                for (String f : required) {
                    if (normalize(f).startsWith(nameSep)) {
                        toRemove.add(f);
                    }
                }
            } else {
                toRemove.add(feature);
            }
            toRemove.retainAll(required);
            if (toRemove.isEmpty()) {
                throw new IllegalArgumentException("Feature named '" + feature + "' is not installed");
            } else if (toRemove.size() > 1) {
                String name = feature.substring(0, feature.indexOf("/"));
                StringBuilder sb = new StringBuilder();
                sb.append("Feature named '").append(name).append("' has multiple versions installed (");
                for (int i = 0; i < toRemove.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(toRemove.get(i));
                }
                sb.append("). Please specify the version to uninstall.");
                throw new IllegalArgumentException(sb.toString());
            }
            featuresToRemove.addAll(toRemove);
        }
        featuresToRemove = new ArrayList<String>(new LinkedHashSet<String>(featuresToRemove));
        StringBuilder sb = new StringBuilder();
        sb.append("Removing features: ");
        for (int i = 0; i < featuresToRemove.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(featuresToRemove.get(i));
        }
        print(sb.toString(), options.contains(Option.Verbose));
        required.removeAll(featuresToRemove);
        doInstallFeaturesInThread(required, managed, options);
    }

    protected String normalize(String feature) {
        if (!feature.contains("/")) {
            feature += "/0.0.0";
        }
        int idx = feature.indexOf("/");
        String name = feature.substring(0, idx);
        String version = feature.substring(idx + 1);
        return name + "/" + VersionTable.getVersion(version).toString();
    }

    /**
     * Actual deployment needs to be done in a separate thread.
     * The reason is that if the console is refreshed, the current thread which is running
     * the command may be interrupted while waiting for the refresh to be done, leading
     * to bundles not being started after the refresh.
     */
    public void doInstallFeaturesInThread(final Set<String> features,
                                          final Set<Long> managed,
                                          final EnumSet<Option> options) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            executor.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    doInstallFeatures(features, managed, options);
                    return null;
                }
            }).get();
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException) {
                throw ((RuntimeException) t);
            } else if (t instanceof Error) {
                throw ((Error) t);
            } else if (t instanceof Exception) {
                throw (Exception) t;
            } else {
                throw e;
            }
        } finally {
            executor.shutdown();
        }
    }

    public void doInstallFeatures(Set<String> features, Set<Long> managed, EnumSet<Option> options) throws Exception {
        // TODO: make this configurable  through ConfigAdmin
        // TODO: this needs to be tested a bit
        // TODO: note that this only applies to managed and updateable bundles
        boolean updateSnaphots = true;

        // TODO: make this configurable at runtime
        // TODO: note that integration tests will fail if set to false
        // TODO: but I think it should be the default anyway
        boolean noRefreshUnmanaged = true;

        // TODO: make this configurable at runtime
        boolean noRefreshManaged = true;

        // TODO: make this configurable at runtime
        boolean noRefresh = false;

        // TODO: make this configurable  through ConfigAdmin
        // TODO: though opening it as some important effects
        String featureResolutionRange = "${range;[====,====]}";

        // TODO: make this configurable through ConfigAdmin
        String bundleUpdateRange = "${range;[==,=+)}";

        boolean verbose = options.contains(Option.Verbose);

        // Get a list of resolved and unmanaged bundles to use as capabilities during resolution
        List<Resource> systemBundles = new ArrayList<Resource>();
        Bundle[] bundles = systemBundleContext.getBundles();
        for (Bundle bundle : bundles) {
            if (bundle.getState() >= Bundle.RESOLVED && !managed.contains(bundle.getBundleId())) {
                Resource res = bundle.adapt(BundleRevision.class);
                systemBundles.add(res);
            }
        }
        // Resolve
        // TODO: requirements
        // TODO: bundles
        // TODO: regions: on isolated regions, we may need different resolution for each region
        Set<String>  overrides    = Overrides.loadOverrides(this.overrides);
        Repository[] repositories = listRepositories();
        DeploymentBuilder builder = createDeploymentBuilder(repositories);
        builder.setFeatureRange(featureResolutionRange);
        builder.download(features,
                         Collections.<String>emptySet(),
                         Collections.<String>emptySet(),
                         overrides,
                         Collections.<String>emptySet());
        Collection<Resource> allResources = builder.resolve(systemBundles, false);
        Map<String, StreamProvider> providers = builder.getProviders();

        // Install conditionals
        List<String> installedFeatureIds = getFeatureIds(allResources);
        List<Feature> installedFeatures = getFeatures(repositories, installedFeatureIds);

        // TODO: is there are a way to use fragments or on-demand resources
        // TODO: in the resolver to use a single resolution ?
        boolean resolveAgain = false;
        Set<String> featuresAndConditionals = new TreeSet<String>(features);
        for (Feature feature : installedFeatures) {
            for (Conditional cond : feature.getConditional()) {
                boolean condSatisfied = true;
                for (Dependency dep : cond.getCondition()) {
                    boolean depSatisfied = false;
                    String name = dep.getName();
                    VersionRange range = new VersionRange(dep.getVersion(), false, true);
                    for (Feature f : installedFeatures) {
                        if (f.getName().equals(name)) {
                            if (range.contains(VersionTable.getVersion(f.getVersion()))) {
                                depSatisfied = true;
                                break;
                            }
                        }
                    }
                    if (!depSatisfied) {
                        condSatisfied = false;
                        break;
                    }
                }
                if (condSatisfied) {
                    featuresAndConditionals.add(cond.asFeature(feature.getName(), feature.getVersion()).getId());
                    resolveAgain = true;
                }
            }
        }
        if (resolveAgain) {
            builder.download(featuresAndConditionals,
                             Collections.<String>emptySet(),
                             Collections.<String>emptySet(),
                             overrides,
                             Collections.<String>emptySet());
            allResources = builder.resolve(systemBundles, false);
            providers = builder.getProviders();
        }


        //
        // Compute list of installable resources (those with uris)
        //
        List<Resource> resources = getBundles(allResources);

        // Compute information for each bundle
        Map<String, BundleInfo> bundleInfos = new HashMap<String, BundleInfo>();
        for (Feature feature : getFeatures(repositories, getFeatureIds(allResources))) {
            for (BundleInfo bi : feature.getBundles()) {
                BundleInfo oldBi = bundleInfos.get(bi.getLocation());
                if (oldBi != null) {
                    bi = mergeBundleInfo(bi, oldBi);
                }
                bundleInfos.put(bi.getLocation(), bi);
            }
        }


        //
        // Compute deployment
        //
        Map<String, Long> bundleChecksums = new HashMap<String, Long>();
        synchronized (lock) {
            bundleChecksums.putAll(state.bundleChecksums);
        }
        Deployment deployment = computeDeployment(managed, updateSnaphots, bundles, providers, resources, bundleChecksums, bundleUpdateRange);

        if (deployment.toDelete.isEmpty() &&
                deployment.toUpdate.isEmpty() &&
                deployment.toInstall.isEmpty()) {
            print("No deployment change.", verbose);
            return;
        }
        //
        // Log deployment
        //
        logDeployment(deployment);


        Set<Bundle> toRefresh = new HashSet<Bundle>();
        Set<Bundle> toStart = new HashSet<Bundle>();

        //
        // Execute deployment
        //

        // TODO: handle update on the features service itself
        if (deployment.toUpdate.containsKey(bundle) ||
                deployment.toDelete.contains(bundle)) {

            LOGGER.warn("Updating or uninstalling of the FeaturesService is not supported");
            deployment.toUpdate.remove(bundle);
            deployment.toDelete.remove(bundle);

        }

        //
        // Perform bundle operations
        //

        // Stop bundles by chunks
        Set<Bundle> toStop = new HashSet<Bundle>();
        toStop.addAll(deployment.toUpdate.keySet());
        toStop.addAll(deployment.toDelete);
        removeFragmentsAndBundlesInState(toStop, Bundle.UNINSTALLED | Bundle.RESOLVED | Bundle.STOPPING);
        if (!toStop.isEmpty()) {
            print("Stopping bundles:", verbose);
            while (!toStop.isEmpty()) {
                List<Bundle> bs = getBundlesToStop(toStop);
                for (Bundle bundle : bs) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                    bundle.stop(Bundle.STOP_TRANSIENT);
                    toStop.remove(bundle);
                }
            }
        }
        if (!deployment.toDelete.isEmpty()) {
            print("Uninstalling bundles:", verbose);
            for (Bundle bundle : deployment.toDelete) {
                print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                bundle.uninstall();
                managed.remove(bundle.getBundleId());
                toRefresh.add(bundle);
            }
        }
        if (!deployment.toUpdate.isEmpty()) {
            print("Updating bundles:", verbose);
            for (Map.Entry<Bundle, Resource> entry : deployment.toUpdate.entrySet()) {
                Bundle bundle = entry.getKey();
                Resource resource = entry.getValue();
                String uri = UriNamespace.getUri(resource);
                print("  " + uri, verbose);
                InputStream is = getBundleInputStream(resource, providers);
                bundle.update(is);
                toRefresh.add(bundle);
                toStart.add(bundle);
                BundleInfo bi = bundleInfos.get(uri);
                if (bi != null && bi.getStartLevel() > 0) {
                    bundle.adapt(BundleStartLevel.class).setStartLevel(bi.getStartLevel());
                }
                // TODO: handle region
            }
        }
        if (!deployment.toInstall.isEmpty()) {
            print("Installing bundles:", verbose);
            for (Resource resource : deployment.toInstall) {
                String uri = UriNamespace.getUri(resource);
                print("  " + uri, verbose);
                InputStream is = getBundleInputStream(resource, providers);
                Bundle bundle = systemBundleContext.installBundle(uri, is);
                managed.add(bundle.getBundleId());
                toStart.add(bundle);
                deployment.resToBnd.put(resource, bundle);
                // save a checksum of installed snapshot bundle
                if (isUpdateable(resource) && !deployment.newCheckums.containsKey(bundle.getLocation())) {
                    deployment.newCheckums.put(bundle.getLocation(), ChecksumUtils.checksum(getBundleInputStream(resource, providers)));
                }
                BundleInfo bi = bundleInfos.get(uri);
                if (bi != null && bi.getStartLevel() > 0) {
                    bundle.adapt(BundleStartLevel.class).setStartLevel(bi.getStartLevel());
                }
                // TODO: handle region
            }
        }

        //
        // Update and save state
        //
        List<String> newFeatures = new ArrayList<String>();
        synchronized (lock) {
            List<String> allFeatures = new ArrayList<String>();
            for (Resource resource : allResources) {
                String name = FeatureNamespace.getName(resource);
                if (name != null) {
                    Version version = FeatureNamespace.getVersion(resource);
                    String id = version != null ? name + "/" + version : name;
                    allFeatures.add(id);
                    if (!state.installedFeatures.contains(id)) {
                        newFeatures.add(id);
                    }
                }
            }
            state.bundleChecksums.putAll(deployment.newCheckums);
            state.features.clear();
            state.features.addAll(features);
            state.installedFeatures.clear();
            state.installedFeatures.addAll(allFeatures);
            state.managedBundles.clear();
            state.managedBundles.addAll(managed);
            saveState();
        }

        //
        // Install configurations
        //
        if (configInstaller != null && !newFeatures.isEmpty()) {
            for (Repository repository : repositories) {
                for (Feature feature : repository.getFeatures()) {
                    if (newFeatures.contains(feature.getId())) {
                        configInstaller.installFeatureConfigs(feature);
                    }
                }
            }
        }

        if (!noRefreshManaged) {
            findBundlesWithOptionalPackagesToRefresh(toRefresh);
            findBundlesWithFragmentsToRefresh(toRefresh);
        }

        if (noRefreshUnmanaged) {
            Set<Bundle> newSet = new HashSet<Bundle>();
            for (Bundle bundle : toRefresh) {
                if (managed.contains(bundle.getBundleId())) {
                    newSet.add(bundle);
                }
            }
            toRefresh = newSet;
        }

        // TODO: remove this hack, but it avoids loading the class after the bundle is refreshed
        RequirementSort sort = new RequirementSort();

        if (!noRefresh) {
            toStop = new HashSet<Bundle>();
            toStop.addAll(toRefresh);
            removeFragmentsAndBundlesInState(toStop, Bundle.UNINSTALLED | Bundle.RESOLVED | Bundle.STOPPING);
            if (!toStop.isEmpty()) {
                print("Stopping bundles:", verbose);
                while (!toStop.isEmpty()) {
                    List<Bundle> bs = getBundlesToStop(toStop);
                    for (Bundle bundle : bs) {
                        print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                        bundle.stop(Bundle.STOP_TRANSIENT);
                        toStop.remove(bundle);
                        toStart.add(bundle);
                    }
                }
            }

            if (!toRefresh.isEmpty()) {
                print("Refreshing bundles:", verbose);
                for (Bundle bundle : toRefresh) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                }
                if (!toRefresh.isEmpty()) {
                    refreshPackages(toRefresh);
                }
            }
        }

        // Compute bundles to start
        removeFragmentsAndBundlesInState(toStart, Bundle.UNINSTALLED | Bundle.ACTIVE | Bundle.STARTING);
        if (!toStart.isEmpty()) {
            // Compute correct start order
            List<Exception> exceptions = new ArrayList<Exception>();
            print("Starting bundles:", verbose);
            while (!toStart.isEmpty()) {
                List<Bundle> bs = getBundlesToStart(toStart);
                for (Bundle bundle : bs) {
                    LOGGER.info("  " + bundle.getSymbolicName() + " / " + bundle.getVersion());
                    try {
                        bundle.start();
                    } catch (BundleException e) {
                        exceptions.add(e);
                    }
                    toStart.remove(bundle);
                }
            }
            if (!exceptions.isEmpty()) {
                throw new MultiException("Error restarting bundles", exceptions);
            }
        }

        print("Done.", verbose);
    }

    protected BundleInfo mergeBundleInfo(BundleInfo bi, BundleInfo oldBi) {
        // TODO: we need a proper merge strategy when a bundle
        // TODO: comes from different features
        return bi;
    }

    private void print(String message, boolean verbose) {
        LOGGER.info(message);
        if (verbose) {
            System.out.println(message);
        }
    }

    private void removeFragmentsAndBundlesInState(Collection<Bundle> bundles, int state) {
        for (Bundle bundle : new ArrayList<Bundle>(bundles)) {
            if ((bundle.getState() & state) != 0
                     || bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null) {
                bundles.remove(bundle);
            }
        }
    }

    protected void logDeployment(Deployment deployment) {
        LOGGER.info("Changes to perform:");
        if (!deployment.toDelete.isEmpty()) {
            LOGGER.info("  Bundles to uninstall:");
            for (Bundle bundle : deployment.toDelete) {
                LOGGER.info("    " + bundle.getSymbolicName() + " / " + bundle.getVersion());
            }
        }
        if (!deployment.toUpdate.isEmpty()) {
            LOGGER.info("  Bundles to update:");
            for (Map.Entry<Bundle, Resource> entry : deployment.toUpdate.entrySet()) {
                LOGGER.info("    " + entry.getKey().getSymbolicName() + " / " + entry.getKey().getVersion() + " with " + UriNamespace.getUri(entry.getValue()));
            }
        }
        if (!deployment.toInstall.isEmpty()) {
            LOGGER.info("  Bundles to install:");
            for (Resource resource : deployment.toInstall) {
                LOGGER.info("    " + UriNamespace.getUri(resource));
            }
        }
    }

    protected Deployment computeDeployment(
                                Set<Long> managed,
                                boolean updateSnaphots,
                                Bundle[] bundles,
                                Map<String, StreamProvider> providers,
                                List<Resource> resources,
                                Map<String, Long> bundleChecksums,
                                String bundleUpdateRange) throws IOException {
        Deployment deployment = new Deployment();

        // TODO: regions
        List<Resource> toDeploy = new ArrayList<Resource>(resources);

        // First pass: go through all installed bundles and mark them
        // as either to ignore or delete
        for (Bundle bundle : bundles) {
            if (bundle.getSymbolicName() != null && bundle.getBundleId() != 0) {
                Resource resource = null;
                for (Resource res : toDeploy) {
                    if (bundle.getSymbolicName().equals(getSymbolicName(res))) {
                        if (bundle.getVersion().equals(getVersion(res))) {
                            resource = res;
                            break;
                        }
                    }
                }
                // We found a matching bundle
                if (resource != null) {
                    // In case of snapshots, check if the snapshot is out of date
                    // and flag it as to update
                    if (updateSnaphots && managed.contains(bundle.getBundleId()) && isUpdateable(resource)) {
                        // if the checksum are different
                        InputStream is = null;
                        try {
                            is = getBundleInputStream(resource, providers);
                            long newCrc = ChecksumUtils.checksum(is);
                            long oldCrc = bundleChecksums.containsKey(bundle.getLocation()) ? bundleChecksums.get(bundle.getLocation()) : 0l;
                            if (newCrc != oldCrc) {
                                LOGGER.debug("New snapshot available for " + bundle.getLocation());
                                deployment.toUpdate.put(bundle, resource);
                                deployment.newCheckums.put(bundle.getLocation(), newCrc);
                            }
                        } finally {
                            if (is != null) {
                                is.close();
                            }
                        }
                    }
                    // We're done for this resource
                    toDeploy.remove(resource);
                    deployment.resToBnd.put(resource, bundle);
                // There's no matching resource
                // If the bundle is managed, we need to delete it
                } else if (managed.contains(bundle.getBundleId())) {
                    deployment.toDelete.add(bundle);
                }
            }
        }

        // Second pass on remaining resources
        for (Resource resource : toDeploy) {
            TreeMap<Version, Bundle> matching = new TreeMap<Version, Bundle>();
            VersionRange range = new VersionRange(Macro.transform(bundleUpdateRange, getVersion(resource).toString()));
            for (Bundle bundle : deployment.toDelete) {
                if (bundle.getSymbolicName().equals(getSymbolicName(resource)) && range.contains(bundle.getVersion())) {
                    matching.put(bundle.getVersion(), bundle);
                }
            }
            if (!matching.isEmpty()) {
                Bundle bundle = matching.lastEntry().getValue();
                deployment.toUpdate.put(bundle, resource);
                deployment.toDelete.remove(bundle);
                deployment.resToBnd.put(resource, bundle);
            } else {
                deployment.toInstall.add(resource);
            }
        }
        return deployment;
    }

    protected List<Resource> getBundles(Collection<Resource> allResources) {
        Map<String, Resource> deploy = new TreeMap<String, Resource>();
        for (Resource res : allResources) {
            String uri = UriNamespace.getUri(res);
            if (uri != null) {
                deploy.put(uri, res);
            }
        }
        return new ArrayList<Resource>(deploy.values());
    }

    protected List<Feature> getFeatures(Repository[] repositories, List<String> featureIds) throws Exception {
        List<Feature> installedFeatures = new ArrayList<Feature>();
        for (Repository repository : repositories) {
            for (Feature feature : repository.getFeatures()) {
                String id = feature.getName() + "/" + VersionTable.getVersion(feature.getVersion());
                if (featureIds.contains(id)) {
                    installedFeatures.add(feature);
                }
            }
        }
        return installedFeatures;
    }

    protected List<String> getFeatureIds(Collection<Resource> allResources) {
        List<String> installedFeatureIds = new ArrayList<String>();
        for (Resource resource : allResources) {
            String name = FeatureNamespace.getName(resource);
            if (name != null) {
                Version version = FeatureNamespace.getVersion(resource);
                String id = version != null ? name + "/" + version : name;
                installedFeatureIds.add(id);
            }
        }
        return installedFeatureIds;
    }

    protected DeploymentBuilder createDeploymentBuilder(Repository[] repositories) {
        return new DeploymentBuilder(new SimpleDownloader(), Arrays.asList(repositories));
    }


    protected boolean isUpdateable(Resource resource) {
        return (getVersion(resource).getQualifier().endsWith(SNAPSHOT) ||
                UriNamespace.getUri(resource).contains(SNAPSHOT) ||
                !UriNamespace.getUri(resource).contains(MAVEN));
    }

    protected List<Bundle> getBundlesToStart(Collection<Bundle> bundles) {
        // TODO: make this pluggable ?
        // TODO: honor respectStartLvlDuringFeatureStartup

        // We hit FELIX-2949 if we don't use the correct order as Felix resolver isn't greedy.
        // In order to minimize that, we make sure we resolve the bundles in the order they
        // are given back by the resolution, meaning that all root bundles (i.e. those that were
        // not flagged as dependencies in features) are started before the others.   This should
        // make sure those important bundles are started first and minimize the problem.

        // Restart the features service last, regardless of any other consideration
        // so that we don't end up with the service trying to do stuff before we're done
        boolean restart = bundles.remove(bundle);

        List<BundleRevision> revs = new ArrayList<BundleRevision>();
        for (Bundle bundle : bundles) {
            revs.add(bundle.adapt(BundleRevision.class));
        }
        List<Bundle> sorted = new ArrayList<Bundle>();
        for (BundleRevision rev : RequirementSort.sort(revs)) {
            sorted.add(rev.getBundle());
        }
        if (restart) {
            sorted.add(bundle);
        }
        return sorted;
    }

    protected List<Bundle> getBundlesToStop(Collection<Bundle> bundles) {
        // TODO: make this pluggable ?
        // TODO: honor respectStartLvlDuringFeatureUninstall

        List<Bundle> bundlesToDestroy = new ArrayList<Bundle>();
        for (Bundle bundle : bundles) {
            ServiceReference[] references = bundle.getRegisteredServices();
            int usage = 0;
            if (references != null) {
                for (ServiceReference reference : references) {
                    usage += getServiceUsage(reference, bundles);
                }
            }
            LOGGER.debug("Usage for bundle {} is {}", bundle, usage);
            if (usage == 0) {
                bundlesToDestroy.add(bundle);
            }
        }
        if (!bundlesToDestroy.isEmpty()) {
            Collections.sort(bundlesToDestroy, new Comparator<Bundle>() {
                public int compare(Bundle b1, Bundle b2) {
                    return (int) (b2.getLastModified() - b1.getLastModified());
                }
            });
            LOGGER.debug("Selected bundles {} for destroy (no services in use)", bundlesToDestroy);
        } else {
            ServiceReference ref = null;
            for (Bundle bundle : bundles) {
                ServiceReference[] references = bundle.getRegisteredServices();
                for (ServiceReference reference : references) {
                    if (getServiceUsage(reference, bundles) == 0) {
                        continue;
                    }
                    if (ref == null || reference.compareTo(ref) < 0) {
                        LOGGER.debug("Currently selecting bundle {} for destroy (with reference {})", bundle, reference);
                        ref = reference;
                    }
                }
            }
            if (ref != null) {
                bundlesToDestroy.add(ref.getBundle());
            }
            LOGGER.debug("Selected bundle {} for destroy (lowest ranking service)", bundlesToDestroy);
        }
        return bundlesToDestroy;
    }

    private static int getServiceUsage(ServiceReference ref, Collection<Bundle> bundles) {
        Bundle[] usingBundles = ref.getUsingBundles();
        int nb = 0;
        if (usingBundles != null) {
            for (Bundle bundle : usingBundles) {
                if (bundles.contains(bundle)) {
                    nb++;
                }
            }
        }
        return nb;
    }

    protected InputStream getBundleInputStream(Resource resource, Map<String, StreamProvider> providers) throws IOException {
        String uri = UriNamespace.getUri(resource);
        if (uri == null) {
            throw new IllegalStateException("Resource has no uri");
        }
        StreamProvider provider = providers.get(uri);
        if (provider == null) {
            throw new IllegalStateException("Resource " + uri + " has no StreamProvider");
        }
        return provider.open();
    }

    protected void findBundlesWithOptionalPackagesToRefresh(Set<Bundle> toRefresh) {
        // First pass: include all bundles contained in these features
        if (toRefresh.isEmpty()) {
            return;
        }
        Set<Bundle> bundles = new HashSet<Bundle>(Arrays.asList(systemBundleContext.getBundles()));
        bundles.removeAll(toRefresh);
        if (bundles.isEmpty()) {
            return;
        }
        // Second pass: for each bundle, check if there is any unresolved optional package that could be resolved
        for (Bundle bundle : bundles) {
            BundleRevision rev = bundle.adapt(BundleRevision.class);
            boolean matches = false;
            if (rev != null) {
                for (BundleRequirement req : rev.getDeclaredRequirements(null)) {
                    if (PackageNamespace.PACKAGE_NAMESPACE.equals(req.getNamespace())
                            && PackageNamespace.RESOLUTION_OPTIONAL.equals(req.getDirectives().get(PackageNamespace.REQUIREMENT_RESOLUTION_DIRECTIVE))) {
                        // This requirement is an optional import package
                        for (Bundle provider : toRefresh) {
                            BundleRevision providerRev = provider.adapt(BundleRevision.class);
                            if (providerRev != null) {
                                for (BundleCapability cap : providerRev.getDeclaredCapabilities(null)) {
                                    if (req.matches(cap)) {
                                        matches = true;
                                        break;
                                    }
                                }
                            }
                            if (matches) {
                                break;
                            }
                        }
                    }
                    if (matches) {
                        break;
                    }
                }
            }
            if (matches) {
                toRefresh.add(bundle);
            }
        }
    }

    protected void findBundlesWithFragmentsToRefresh(Set<Bundle> toRefresh) {
        if (toRefresh.isEmpty()) {
            return;
        }
        Set<Bundle> bundles = new HashSet<Bundle>(Arrays.asList(systemBundleContext.getBundles()));
        bundles.removeAll(toRefresh);
        if (bundles.isEmpty()) {
            return;
        }
        for (Bundle bundle : new ArrayList<Bundle>(toRefresh)) {
            BundleRevision rev = bundle.adapt(BundleRevision.class);
            if (rev != null) {
                for (BundleRequirement req : rev.getDeclaredRequirements(null)) {
                    if (BundleRevision.HOST_NAMESPACE.equals(req.getNamespace())) {
                        for (Bundle hostBundle : bundles) {
                            if (!toRefresh.contains(hostBundle)) {
                                BundleRevision hostRev = hostBundle.adapt(BundleRevision.class);
                                if (hostRev != null) {
                                    for (BundleCapability cap : hostRev.getDeclaredCapabilities(null)) {
                                        if (req.matches(cap)) {
                                            toRefresh.add(hostBundle);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    protected void refreshPackages(Collection<Bundle> bundles) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        FrameworkWiring fw = systemBundleContext.getBundle().adapt(FrameworkWiring.class);
        fw.refreshBundles(bundles, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR) {
                    LOGGER.error("Framework error", event.getThrowable());
                }
                latch.countDown();
            }
        });
        latch.await();
    }


    static class Deployment {
        Map<String, Long> newCheckums = new HashMap<String, Long>();
        Map<Resource, Bundle> resToBnd = new HashMap<Resource, Bundle>();
        List<Resource> toInstall = new ArrayList<Resource>();
        List<Bundle> toDelete = new ArrayList<Bundle>();
        Map<Bundle, Resource> toUpdate = new HashMap<Bundle, Resource>();
    }

}
