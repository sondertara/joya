/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.support.dialect.provider;


import com.sondertara.joya.core.jdbc.support.dialect.ConditionSqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 公共 SqlDialect 实现
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2020-10-31
 */
public abstract class AbstractDialect implements SqlDialect, ConditionSqlDialect {
    private static final Logger logger = LoggerFactory.getLogger(AbstractDialect.class);
    private Set<String> keyWords;

    @Override
    public final Set<String> keywords() {
        if (this.keyWords == null) {
            this.keyWords = new HashSet<>();

            // 加载 META-INF/custom.keywords
            List<URL> ins = getResources("META-INF/custom.keywords");
            for (URL in : ins) {
                try (InputStream input = in.openStream()) {
                    List<String> strings = readLines(input, StandardCharsets.UTF_8);
                    this.loadKeyWords(strings);
                } catch (Exception e) {
                    logger.error("load '" + in + "' failed." + e.getMessage());
                }
            }

            String keyWordsResource = keyWordsResource();
            if (isBlank(keyWordsResource)) {
                return this.keyWords;
            }

            try (InputStream stream = getResourceAsStream(keyWordsResource)) {
                if (stream != null) {
                    List<String> strings = readLines(stream, StandardCharsets.UTF_8);
                    this.loadKeyWords(strings);
                }
            } catch (Exception e) {
                logger.error("load keywords '" + keyWordsResource + "' failed." + e.getMessage());
            }
        }
        return this.keyWords;
    }

    private void loadKeyWords(List<String> strings) {
        for (String term : strings) {
            term = term.trim().toUpperCase();
            if (!isBlank(term) && term.charAt(0) != '#') {
                this.keyWords.add(term);
            }
        }
    }

    protected String keyWordsResource() {
        return null;
    }

    @Override
    public String tableName(boolean useQualifier, String catalog, String schema, String table) {
        if (isBlank(schema)) {
            return fmtName(useQualifier, table);
        } else {
            return fmtName(useQualifier, schema) + "." + fmtName(useQualifier, table);
        }
    }

    @Override
    public String fmtName(boolean useQualifier, String name) {
        if (isBlank(name)) {
            return name;
        }
        if (this.keywords().contains(name.toUpperCase())) {
            useQualifier = true;
        }
        String leftQualifier = useQualifier ? leftQualifier() : "";
        String rightQualifier = useQualifier ? rightQualifier() : "";
        return leftQualifier + name + rightQualifier;
    }

    protected String defaultQualifier() {
        return "";
    }

    @Override
    public String leftQualifier() {
        return this.defaultQualifier();
    }

    @Override
    public String rightQualifier() {
        return this.defaultQualifier();
    }

    // ============ JDK 原生资源加载 ============

    private List<URL> getResources(String resource) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                Enumeration<URL> urls = cl.getResources(resource);
                if (urls.hasMoreElements()) {
                    List<URL> result = new ArrayList<>();
                    while (urls.hasMoreElements()) {
                        result.add(urls.nextElement());
                    }
                    return result;
                }
            }
        } catch (IOException ignored) {
        }
        return Collections.emptyList();
    }

    private InputStream getResourceAsStream(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            InputStream is = cl.getResourceAsStream(resource);
            if (is != null) {
                return is;
            }
        }
        return getClass().getClassLoader().getResourceAsStream(resource);
    }

    private List<String> readLines(InputStream input, java.nio.charset.Charset charset) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        }
    }

    private static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

}