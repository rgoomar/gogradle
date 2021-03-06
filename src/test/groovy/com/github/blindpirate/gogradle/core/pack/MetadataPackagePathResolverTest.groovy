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

package com.github.blindpirate.gogradle.core.pack

import com.github.blindpirate.gogradle.GogradleRunner
import com.github.blindpirate.gogradle.core.GolangPackage
import com.github.blindpirate.gogradle.support.MockOffline
import com.github.blindpirate.gogradle.util.HttpUtils
import com.github.blindpirate.gogradle.vcs.VcsType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock

import static com.github.blindpirate.gogradle.core.pack.MetadataPackagePathResolver.GO_USER_AGENT
import static com.github.blindpirate.gogradle.util.HttpUtils.USER_AGENT
import static org.mockito.ArgumentMatchers.anyMap
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

@RunWith(GogradleRunner)
@MockOffline(false)
class MetadataPackagePathResolverTest {

    @Mock
    HttpUtils httpUtils

    MetadataPackagePathResolver resolver

    @Before
    void setUp() {
        resolver = new MetadataPackagePathResolver(httpUtils)
        when(httpUtils.appendQueryParams(anyString(), anyMap())).thenCallRealMethod()
    }

    @Test
    @MockOffline(true)
    void 'empty result should be returned when offline'() {
        // then
        assert !resolver.produce('').isPresent()
    }

    @Test
    void 'get package info from go-import meta tag should succeed'() {
        // given
        String packagePath = 'example.org/pkg/foo'
        String realUrl = 'https://example.org/pkg/foo?go-get=1'
        String metaTag = '<meta name="go-import" content="example.org git https://code.org/r/p/exproj">'
        when(httpUtils.get(realUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(metaTag))

        // when
        GolangPackage info = resolver.produce(packagePath).get()

        // then
        assert info.urls == ['https://code.org/r/p/exproj']
        assert info.vcsType == VcsType.GIT
        assert info.pathString == packagePath
        assert info.rootPathString == 'example.org'
    }

    @Test
    void 'http should be tried when https failed'() {
        // given
        String packagePath = 'example.org/pkg/foo'
        String realHttpsUrl = 'https://example.org/pkg/foo?go-get=1'
        String realHttpUrl = 'http://example.org/pkg/foo?go-get=1'
        String metaTag = '<meta name="go-import" content="example.org git https://code.org/r/p/exproj">'
        when(httpUtils.get(realHttpsUrl, [(USER_AGENT): GO_USER_AGENT])).thenThrow(new IOException())
        when(httpUtils.get(realHttpUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(metaTag))

        // when
        resolver.produce(packagePath).get()

        // then
        verify(httpUtils).get(realHttpUrl, [(USER_AGENT): GO_USER_AGENT])
    }

    @Test
    void 'missing meta tag should result in empty result'() {
        // given
        String packagePath = 'example.org/pkg/foo'
        String realHttpsUrl = 'https://example.org/pkg/foo?go-get=1'
        String realHttpUrl = 'http://example.org/pkg/foo?go-get=1'
        when(httpUtils.get(realHttpsUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(''))
        when(httpUtils.get(realHttpUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(''))

        // then
        assert !resolver.produce(packagePath).isPresent()
    }

    @Test(expected = IllegalStateException)
    void 'invalid meta tag should result in an exception'() {
        // given
        String packagePath = 'example.org/pkg/foo'
        String realHttpsUrl = 'https://example.org/pkg/foo?go-get=1'
        String metaTag = '<meta name="go-import" content="example.org git">'
        when(httpUtils.get(realHttpsUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(metaTag))

        // then
        resolver.produce(packagePath)
    }

    @Test
    void 'matched tag should be found out in multiple meta tags'() {
        // given
        String packagePath = 'bazil.org/fuse/fs'
        String realHttpsUrl = 'https://bazil.org/fuse/fs?go-get=1'
        String metaTag = '''
        <meta name="go-import" content="bazil.org/bazil git https://github.com/bazil/bazil">
        <meta name="go-import" content="bazil.org/fuse git https://github.com/bazil/fuse">
        <meta name="go-import" content="bazil.org/bolt-mount git https://github.com/bazil/bolt-mount">
        '''
        when(httpUtils.get(realHttpsUrl, [(USER_AGENT): GO_USER_AGENT])).thenReturn(tagInHtml(metaTag))
        // when
        GolangPackage pkg = resolver.produce(packagePath).get()
        // then
        assert pkg.pathString == 'bazil.org/fuse/fs'
        assert pkg.rootPathString == 'bazil.org/fuse'
        assert pkg.vcsType == VcsType.GIT
    }

    String tagInHtml(String s) {
        return "<html><header>${s}</header><body></body></html>"
    }
}
