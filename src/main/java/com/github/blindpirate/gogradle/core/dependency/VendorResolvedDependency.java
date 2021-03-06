/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.blindpirate.gogradle.core.dependency;

import com.github.blindpirate.gogradle.GogradleGlobal;
import com.github.blindpirate.gogradle.core.cache.CacheScope;
import com.github.blindpirate.gogradle.core.cache.ProjectCacheManager;
import com.github.blindpirate.gogradle.core.dependency.install.LocalDirectoryDependencyManager;
import com.github.blindpirate.gogradle.core.dependency.produce.DependencyVisitor;
import com.github.blindpirate.gogradle.core.dependency.resolve.DependencyManager;
import com.github.blindpirate.gogradle.util.MapUtils;
import com.github.blindpirate.gogradle.util.StringUtils;
import com.github.blindpirate.gogradle.vcs.VcsAccessor;
import com.github.blindpirate.gogradle.vcs.VcsResolvedDependency;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.github.blindpirate.gogradle.core.GolangConfiguration.BUILD;
import static com.github.blindpirate.gogradle.core.dependency.parse.MapNotationParser.HOST_KEY;
import static com.github.blindpirate.gogradle.core.dependency.parse.MapNotationParser.NAME_KEY;
import static com.github.blindpirate.gogradle.core.dependency.parse.MapNotationParser.VENDOR_PATH_KEY;
import static com.github.blindpirate.gogradle.core.dependency.produce.VendorDependencyFactory.VENDOR_DIRECTORY;
import static com.github.blindpirate.gogradle.util.StringUtils.toUnixString;

public class VendorResolvedDependency extends AbstractResolvedDependency {
    private ResolvedDependency hostDependency;

    // java.io.NotSerializableException: sun.nio.fs.UnixPath
    private String relativePathToHost;

    protected VendorResolvedDependency(String name,
                                       String version,
                                       long updateTime,
                                       ResolvedDependency hostDependency,
                                       String relativePathToHost) {
        super(name, version, updateTime);
        this.hostDependency = hostDependency;
        this.relativePathToHost = relativePathToHost;
    }

    public static VendorResolvedDependency fromParent(String name,
                                                      ResolvedDependency parent,
                                                      File rootDirOfThisVendor) {
        ResolvedDependency hostDependency = determineHostDependency(parent);
        Path relativePathToHost = calculateRootPathToHost(parent, name);
        File hostRootDir = calculateHostRootDir(rootDirOfThisVendor, relativePathToHost);
        String version = hostDependency.toString() + "/" + StringUtils.toUnixString(relativePathToHost);
        long updateTime = determineUpdateTime(hostDependency, hostRootDir, rootDirOfThisVendor, relativePathToHost);

        VendorResolvedDependency ret = new VendorResolvedDependency(name,
                version,
                updateTime,
                hostDependency,
                StringUtils.toUnixString(relativePathToHost));

        DependencyVisitor visitor = GogradleGlobal.getInstance(DependencyVisitor.class);
        ProjectCacheManager projectCacheManager = GogradleGlobal.getInstance(ProjectCacheManager.class);

        GolangDependencySet dependencies = projectCacheManager.produce(ret,
                resolvedDependency -> visitor.visitVendorDependencies(resolvedDependency, rootDirOfThisVendor, BUILD));

        ret.setDependencies(dependencies);
        return ret;
    }

    private static File calculateHostRootDir(File rootDirOfThisVendor, Path relativePathToHost) {
        // <hostRoot>/vendor/a/vendor, vendor/a/vendor -> <hostRoot>
        File ret = rootDirOfThisVendor;
        for (int i = 0; i < relativePathToHost.getNameCount(); ++i) {
            ret = ret.getParentFile();
        }
        return ret;
    }

    private static long determineUpdateTime(ResolvedDependency hostDependency,
                                            File hostRootDir,
                                            File rootDirOfThisVendor,
                                            Path relativePathToHost) {
        if (hostDependency instanceof VcsResolvedDependency) {
            return VcsResolvedDependency.class.cast(hostDependency).getVcsType()
                    .getService(VcsAccessor.class).lastCommitTimeOfPath(hostRootDir, relativePathToHost);
        } else if (hostDependency instanceof LocalDirectoryDependency) {
            return rootDirOfThisVendor.lastModified();
        } else {
            throw new IllegalStateException();
        }
    }

    private static Path calculateRootPathToHost(ResolvedDependency parent, String packagePath) {
        if (parent instanceof VendorResolvedDependency) {
            VendorResolvedDependency parentVendorResolvedDependency = (VendorResolvedDependency) parent;
            return Paths.get(parentVendorResolvedDependency.relativePathToHost)
                    .resolve(VENDOR_DIRECTORY).resolve(packagePath);
        } else {
            return Paths.get(VENDOR_DIRECTORY).resolve(packagePath);
        }
    }

    private static ResolvedDependency determineHostDependency(ResolvedDependency parent) {
        if (parent instanceof VendorResolvedDependency) {
            return VendorResolvedDependency.class.cast(parent).hostDependency;
        } else {
            return parent;
        }
    }

    public ResolvedDependency getHostDependency() {
        return hostDependency;
    }

    void setHostDependency(ResolvedDependency hostDependency) {
        this.hostDependency = hostDependency;
    }

    public String getRelativePathToHost() {
        return relativePathToHost;
    }

    @Override
    protected DependencyManager getInstaller() {
        if (hostDependency instanceof LocalDirectoryDependency) {
            return GogradleGlobal.getInstance(LocalDirectoryDependencyManager.class);
        } else {
            return AbstractResolvedDependency.class.cast(hostDependency).getInstaller();
        }
    }

    @Override
    public String formatVersion() {
        return getVersion();
    }

    @Override
    public Map<String, Object> toLockedNotation() {
        Map<String, Object> ret = MapUtils.asMap(NAME_KEY, getName());
        Map<String, Object> host = new HashMap<>(hostDependency.toLockedNotation());
        ret.put(VENDOR_PATH_KEY, toUnixString(relativePathToHost));
        ret.put(HOST_KEY, host);
        return ret;
    }

    @Override
    public CacheScope getCacheScope() {
        return hostDependency.getCacheScope();
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        VendorResolvedDependency that = (VendorResolvedDependency) o;
        return Objects.equals(hostDependency, that.hostDependency)
                && Objects.equals(relativePathToHost, that.relativePathToHost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostDependency, relativePathToHost);
    }
}
