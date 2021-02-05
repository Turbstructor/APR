/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cocoon.core.container.spring.avalon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.excalibur.source.Source;
import org.apache.excalibur.source.SourceResolver;
import org.apache.excalibur.source.SourceUtil;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;

public class SourceResourceLoader implements ResourceLoader {

    protected final ResourceLoader wrappedLoader;

    protected final SourceResolver resolver;

    public SourceResourceLoader(ResourceLoader wrappedLoader, SourceResolver resolver) {
        this.wrappedLoader = wrappedLoader;
        this.resolver = resolver;
    }

    /**
     * @see org.springframework.core.io.ResourceLoader#getClassLoader()
     */
    public ClassLoader getClassLoader() {
        return this.wrappedLoader.getClassLoader();
    }

    /**
     * @see org.springframework.core.io.ResourceLoader#getResource(java.lang.String)
     */
    public Resource getResource(String location) {
        if ( location != null && location.indexOf(':') > 0 ) {
            try {
                return new UrlResource(location);
            } catch (MalformedURLException e) {
                // we ignore it and leave it up to the wrapped loader
            }
        }
        return this.wrappedLoader.getResource(location);
    }

    public static class SourceResource implements Resource {

        protected Source source;
        protected SourceResolver resolver;
        protected boolean open = false;

        public SourceResource(Source s, SourceResolver r) {
            this.source = s;
            this.resolver =r;
        }

        /**
         * @see org.springframework.core.io.InputStreamSource#getInputStream()
         */
        public InputStream getInputStream() throws IOException {
            this.open = true;
            return new SourceIOInputStream(this.resolver, this.source);
        }

        /**
         * @see org.springframework.core.io.Resource#createRelative(java.lang.String)
         */
        public Resource createRelative(String uri) throws IOException {
            int pos = this.source.getURI().lastIndexOf('/');
            return new SourceResource(this.resolver.resolveURI(uri, this.source.getURI().substring(0, pos), null), this.resolver);
        }

        /**
         * @see org.springframework.core.io.Resource#exists()
         */
        public boolean exists() {
            return this.source.exists();
        }

        /**
         * @see org.springframework.core.io.Resource#getDescription()
         */
        public String getDescription() {
            return "Source: " + this.source;
        }

        /**
         * @see org.springframework.core.io.Resource#getFile()
         */
        public File getFile() throws IOException {
            return SourceUtil.getFile(this.source);
        }

        /**
         * @see org.springframework.core.io.Resource#getFilename()
         */
        public String getFilename() {
            int pos = this.source.getURI().lastIndexOf('/');
            return this.source.getURI().substring(pos + 1);
        }

        /**
         * @see org.springframework.core.io.Resource#getURL()
         */
        public URL getURL() throws IOException {
            return new URL(this.source.getURI());
        }

        /**
         * @see org.springframework.core.io.Resource#isOpen()
         */
        public boolean isOpen() {
            return this.open;
        }

    }
}