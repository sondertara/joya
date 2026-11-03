package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构边界与不变量守卫测试。
 *
 * <p>设计原则：<b>白名单，而非黑名单</b>。黑名单只能拦住"已经想到的"违规，
 * 任何新增的跨层依赖都会静默通过；白名单则在第一次出现时即失败。
 *
 * <ul>
 *   <li>{@code kernel} 只允许依赖 {@code kernel} 自身、{@code utils}、{@code exceptions}。</li>
 *   <li>{@code ExecutionKernel} 公开签名不得暴露 {@code PageResult} / {@code PageRequest}。</li>
 *   <li>DQL / DML 文本只允许 {@link SqlRenderer} 拼接（方言层的分页语法除外）。</li>
 * </ul>
 */
class ArchitectureGuardTest {

    private static final String PROJECT_PREFIX = "com.sondertara.joya.";

    private static Path resolveMainJavaRoot() {
        Path direct = Path.of("src/main/java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Path.of("joya-core/src/main/java");
    }

    private static Path corePackage(String relative) {
        return resolveMainJavaRoot().resolve("com/sondertara/joya/core").resolve(relative);
    }

    private static Path rootPackage(String relative) {
        return resolveMainJavaRoot().resolve("com/sondertara/joya").resolve(relative);
    }

    @Test
    void exceptionsMayNotDependOnAnyOtherProjectPackage() throws IOException {
        assertOnlyAllowedImports(rootPackage("exceptions"), Set.of(
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void functionMayNotDependOnAnyOtherProjectPackage() throws IOException {
        assertOnlyAllowedImports(corePackage("function"), Set.of(
                "com.sondertara.joya.core.function"
        ));
    }

    @Test
    void utilsMayOnlyDependOnUtilsExceptionsAndFunction() throws IOException {
        assertOnlyAllowedImports(rootPackage("utils"), Set.of(
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions",
                "com.sondertara.joya.core.function"
        ));
    }

    @Test
    void listenerMayOnlyDependOnListenerAndKernelOrdered() throws IOException {
        assertOnlyAllowedImports(corePackage("listener"), Set.of(
                "com.sondertara.joya.core.listener",
                "com.sondertara.joya.core.kernel.support.Ordered"
        ));
    }

    @Test
    void statementLayerMayNotDependOnCoreFacadesOrJdbc() throws IOException {
        assertOnlyAllowedImports(corePackage("statement"), Set.of(
                "com.sondertara.joya.core.statement",
                "com.sondertara.joya.core.query",
                "com.sondertara.joya.core.metadata",
                "com.sondertara.joya.core.naming",
                "com.sondertara.joya.core.kernel",
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void kernelMayOnlyDependOnKernelNamingUtilsAndExceptions() throws IOException {
        assertOnlyAllowedImports(corePackage("kernel"), Set.of(
                "com.sondertara.joya.core.kernel",
                "com.sondertara.joya.core.naming",
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void namingMayNotDependOnAnyOtherProjectPackage() throws IOException {
        assertOnlyAllowedImports(corePackage("naming"), Set.of(
                "com.sondertara.joya.core.naming"
        ));
    }

    @Test
    void modelMayNotDependOnQueryOrKernel() throws IOException {
        assertOnlyAllowedImports(corePackage("model"), Set.of(
                "com.sondertara.joya.core.model"
        ));
    }

    @Test
    void metadataMayNotDependOnQueryOrKernel() throws IOException {
        assertOnlyAllowedImports(corePackage("metadata"), Set.of(
                "com.sondertara.joya.core.metadata",
                "com.sondertara.joya.core.naming",
                "com.sondertara.joya.core.function",
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void executionKernelPublicSignaturesMustNotExposePageResultOrPageRequest() {
        for (Method method : ExecutionKernel.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String returnType = method.getReturnType().getName();
            assertTrue(!returnType.contains("PageResult") && !returnType.contains("PageRequest"),
                    "ExecutionKernel public method must not return PageResult/PageRequest: " + method);
            for (Class<?> paramType : method.getParameterTypes()) {
                String paramName = paramType.getName();
                assertTrue(!paramName.contains("PageResult") && !paramName.contains("PageRequest"),
                        "ExecutionKernel public method must not accept PageResult/PageRequest: " + method);
            }
        }
    }

    @Test
    void sqlTextMustOnlyBeBuiltInsideSqlRendererOrDialectLayer() throws IOException {
        List<String> violations = new ArrayList<>();
        Path mainRoot = resolveMainJavaRoot();
        try (Stream<Path> paths = Files.walk(mainRoot)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = stripComments(Files.readString(file));
                String upper = content.toUpperCase(Locale.ROOT);
                String relative = mainRoot.relativize(file).toString().replace('\\', '/');
                boolean allowedRenderer = relative.equals("com/sondertara/joya/core/kernel/statement/SqlRenderer.java");
                boolean allowedDialect = relative.startsWith("com/sondertara/joya/core/kernel/dialect/");
                if (allowedRenderer || allowedDialect) {
                    continue;
                }
                if (upper.contains("APPEND(\"INSERT INTO ")
                        || upper.contains("\"INSERT INTO \" +")
                        || upper.contains("APPEND(\"UPDATE \")")
                        || upper.contains("\"UPDATE \" +")
                        || upper.contains("APPEND(\"DELETE FROM ")
                        || upper.contains("\"DELETE FROM \" +")
                        || upper.contains("\"TRUNCATE TABLE \" +")
                        || upper.contains("\"LIMIT \" +")
                        || upper.contains("APPEND(\"SELECT ")
                        || upper.contains("\"SELECT \" +")
                        || upper.contains("\"SELECT *")
                        || upper.contains("\"SELECT COUNT")
                        || upper.contains("\"SELECT 1")
                        || upper.contains("\"SELECT DISTINCT")) {
                    violations.add(relative);
                }
            }
        }
        assertEquals(List.of(), violations,
                "Only SqlRenderer.java (plus kernel/dialect pagination syntax) may assemble SQL text; violations: "
                        + violations);
    }

    @Test
    void mapperFacadeMustNotDependOnJdbcImplementations() throws IOException {
        Path mapperDir = corePackage("mapper");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(mapperDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = stripComments(Files.readString(file));
                if (content.contains("com.sondertara.joya.core.jdbc")) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), violations,
                "core/mapper facade must not depend on core.jdbc implementations: " + violations);
    }

    @Test
    void jdbcAdapterMayNotDependOnCoreFacades() throws IOException {
        assertOnlyAllowedImports(corePackage("jdbc"), Set.of(
                "com.sondertara.joya.core.jdbc",
                "com.sondertara.joya.core.function",
                "com.sondertara.joya.core.metadata",
                "com.sondertara.joya.core.model",
                "com.sondertara.joya.core.naming",
                "com.sondertara.joya.core.kernel",
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void queryLayerMayNotDependOnJdbcAdapters() throws IOException {
        assertOnlyAllowedImports(corePackage("query"), Set.of(
                "com.sondertara.joya.core.query",
                "com.sondertara.joya.core.kernel",
                "com.sondertara.joya.core.metadata",
                "com.sondertara.joya.core.model",
                "com.sondertara.joya.core.naming",
                "com.sondertara.joya.core.function",
                "com.sondertara.joya.utils",
                "com.sondertara.joya.exceptions"
        ));
    }

    @Test
    void queryStatementAndMapperLayersMustNotBypassTemplateExecution() throws IOException {
        for (String sub : List.of("query", "statement", "mapper")) {
            try (Stream<Path> paths = Files.walk(corePackage(sub))) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    assertSourceDoesNotContain(file, ".connections()", ".dataSource()");
                }
            }
        }
    }

    @Test
    void txEnginesMustBorrowConnectionsOnlyThroughConnectionProvider() throws IOException {
        try (Stream<Path> paths = Files.walk(corePackage("tx"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertSourceDoesNotContain(file, ".dataSource()", "getConnection(");
            }
        }
    }

    @Test
    void jdbcExecutorIsOnlyReferencedInsideTheExecutorPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        Path mainRoot = resolveMainJavaRoot();
        try (Stream<Path> paths = Files.walk(mainRoot)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = mainRoot.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("com/sondertara/joya/core/kernel/executor/")) {
                    continue;
                }
                if (stripComments(Files.readString(file)).contains("JdbcExecutor")) {
                    violations.add(relative);
                }
            }
        }
        assertEquals(List.of(), violations,
                "JdbcExecutor is an adapter behind the Executor port; depend on Executor / Executor.Factory instead");
    }

    @Test
    void txEngineSpiMustNotExposeTxFacade() throws NoSuchMethodException {
        Method open = com.sondertara.joya.core.tx.TxEngine.class.getMethod("open",
                ExecutionKernel.class, com.sondertara.joya.core.tx.TxOptions.class);
        assertEquals(com.sondertara.joya.core.tx.TxSession.class, open.getReturnType(),
                "TxEngine 只产出物理会话；Tx 门面由 TxStarter 统一包装为 TxImpl");
    }

    /**
     * 门面簇：{@code JoyaClient → TxStarter}、{@code Tx extends DataOps}、{@code JoyaClient → JdkMapperProxy}、
     * {@code JoyaMapper → DataOps} 天然互相依赖，视为同一层的三个切片，显式豁免；除此之外不允许任何包级循环。
     */
    private static final Set<String> FACADE_CLUSTER = Set.of(
            "com.sondertara.joya.core",
            "com.sondertara.joya.core.mapper",
            "com.sondertara.joya.core.tx");

    @Test
    void noPackageCyclesExceptTheFacadeCluster() throws IOException {
        java.util.Map<String, Set<String>> graph = new java.util.TreeMap<>();
        try (Stream<Path> paths = Files.walk(resolveMainJavaRoot())) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = stripComments(Files.readString(file));
                java.util.regex.Matcher pkgMatcher = java.util.regex.Pattern
                        .compile("(?m)^package\\s+([\\w.]+);").matcher(content);
                assertTrue(pkgMatcher.find(), "missing package declaration: " + file);
                String owner = pkgMatcher.group(1);
                Set<String> deps = graph.computeIfAbsent(owner, k -> new java.util.TreeSet<>());
                java.util.regex.Matcher ref = java.util.regex.Pattern
                        .compile("com\\.sondertara\\.joya(?:\\.[a-z][A-Za-z0-9_]*)+").matcher(content);
                while (ref.find()) {
                    String target = ref.group();
                    if (!target.equals(owner)) {
                        deps.add(target);
                    }
                }
            }
        }
        // 只保留指向“已存在源码包”的边（过滤掉 import 末段小写的非包片段）
        for (Set<String> deps : graph.values()) {
            deps.retainAll(graph.keySet());
        }
        List<String> violations = new ArrayList<>();
        for (List<String> component : stronglyConnectedComponents(graph)) {
            if (component.size() > 1 && !FACADE_CLUSTER.containsAll(component)) {
                violations.add(String.join(" <-> ", component));
            }
        }
        assertEquals(List.of(), violations,
                "Package dependency cycles are not allowed (only the facade cluster "
                        + FACADE_CLUSTER + " may be mutually dependent): " + violations);
    }

    @Test
    void sqlRendererExposesOnlyAstRenderEntryPoint() {
        List<String> publicMethods = new ArrayList<>();
        for (Method method : SqlRenderer.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods.add(method.getName());
            }
        }
        assertEquals(List.of("render"), publicMethods,
                "SqlRenderer must only expose AST-based render(SqlNode, StatementMetadata)");
    }

    @Test
    void allPackagesEnforceClassLevelCycleGuardsExceptCohesiveGroups() throws IOException {
        assertNoClassCycles(corePackage(""));
        assertNoClassCycles(corePackage("statement"));
        assertNoClassCycles(corePackage("statement/factory"));
        assertNoClassCycles(corePackage("metadata"));
        assertNoClassCycles(corePackage("naming"));
        assertNoClassCycles(corePackage("model"));
        assertNoClassCycles(corePackage("function"));
        assertNoClassCycles(corePackage("listener"));
        assertNoClassCycles(corePackage("query/criterion"));
        assertNoClassCycles(corePackage("query/pagination"));
        assertNoClassCycles(corePackage("query/dynamic"));
        assertNoClassCycles(corePackage("kernel"));
        assertNoClassCycles(corePackage("kernel/connection"));
        assertNoClassCycles(corePackage("kernel/dialect"));
        assertNoClassCycles(corePackage("kernel/error"));
        assertNoClassCycles(corePackage("kernel/executor"));
        assertNoClassCycles(corePackage("kernel/interceptor"));
        assertNoClassCycles(corePackage("kernel/observe"));
        assertNoClassCycles(corePackage("kernel/support"));
        assertNoClassCycles(corePackage("mapper"));
        assertNoClassCycles(corePackage("jdbc/resultset"));
        assertNoClassCycles(corePackage("jdbc/resultset/mapper"));
        assertNoClassCycles(corePackage("kernel/statement"),
                Set.of(Set.of("ParameterBinder", "SqlParameter")));
        assertNoClassCycles(corePackage("tx"),
                Set.of(Set.of("IllegalTxStateException", "TxState"),
                        Set.of("Tx", "TxCallback", "TxConsumer")));
        assertNoClassCycles(corePackage("query"),
                Set.of(Set.of("ColumnMetaProvider", "Expr", "JoinPart", "OnPredicate", "RenderContext", "SqlPredicate")));
        assertNoClassCycles(corePackage("query/dsl"),
                Set.of(Set.of("Query", "QueryExecutor", "Select1", "Select2", "Select3",
                        "Select4", "Select5", "Select6", "Select7", "Select8", "SelectN")));
    }

    @Test
    void statementFactoriesMustNotUseRawNodesForTablesOrSelects() throws IOException {
        try (Stream<Path> files = Files.list(corePackage("statement/factory"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertSourceDoesNotContain(file, "SqlNodes.raw(", "new SqlNode.Raw(");
            }
        }
    }

    @Test
    void coreDoesNotDependOnSpringAndSpringStarterRespectsLayering() throws IOException {
        try (Stream<Path> paths = Files.walk(resolveMainJavaRoot())) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertSourceDoesNotContain(file, "com.sondertara.joya.spring", "org.springframework");
            }
        }
        Path springRoot = Path.of("joya-spring-boot-starter/src/main/java/com/sondertara/joya/spring");
        if (!Files.isDirectory(springRoot)) {
            springRoot = Path.of("../joya-spring-boot-starter/src/main/java/com/sondertara/joya/spring");
        }
        if (Files.isDirectory(springRoot)) {
            assertOnlyAllowedImports(springRoot.resolve("support"), Set.of(
                    "com.sondertara.joya.spring.support",
                    "com.sondertara.joya.core",
                    "com.sondertara.joya.exceptions",
                    "com.sondertara.joya.utils"
            ));
            assertNoClassCycles(springRoot.resolve("support"));
            assertNoClassCycles(springRoot.resolve("autoconfigure"));
        }
    }

    /** 同一目录（不含子目录）内的类级循环检测：以简单类名的词边界引用作为依赖边。 */
    private static void assertNoClassCycles(Path dir) throws IOException {
        assertNoClassCycles(dir, Set.of());
    }

    private static void assertNoClassCycles(Path dir, Set<Set<String>> allowedCohesiveGroups) throws IOException {
        java.util.Map<String, String> sources = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                sources.put(name, stripComments(Files.readString(file)));
            }
        }
        java.util.Map<String, Set<String>> graph = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, String> entry : sources.entrySet()) {
            Set<String> deps = graph.computeIfAbsent(entry.getKey(), k -> new java.util.TreeSet<>());
            for (String other : sources.keySet()) {
                if (!other.equals(entry.getKey())
                        && java.util.regex.Pattern.compile("\\b" + other + "\\b").matcher(entry.getValue()).find()) {
                    deps.add(other);
                }
            }
        }
        List<String> violations = new ArrayList<>();
        for (List<String> component : stronglyConnectedComponents(graph)) {
            if (component.size() > 1 && !allowedCohesiveGroups.contains(Set.copyOf(component))) {
                violations.add(String.join(" <-> ", component));
            }
        }
        assertEquals(List.of(), violations, "Class-level cycles in " + dir + ": " + violations);
    }

    /** Tarjan 强连通分量（纯 JDK 实现，保证守卫测试零额外依赖）。 */
    private static List<List<String>> stronglyConnectedComponents(java.util.Map<String, Set<String>> graph) {
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        java.util.Map<String, Integer> low = new java.util.HashMap<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        List<List<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String node : graph.keySet()) {
            if (!index.containsKey(node)) {
                strongConnect(node, graph, index, low, stack, onStack, result, counter);
            }
        }
        return result;
    }

    private static void strongConnect(String v, java.util.Map<String, Set<String>> graph,
                                      java.util.Map<String, Integer> index, java.util.Map<String, Integer> low,
                                      java.util.Deque<String> stack, Set<String> onStack,
                                      List<List<String>> result, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : graph.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, graph, index, low, stack, onStack, result, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            java.util.Collections.sort(component);
            result.add(component);
        }
    }

    // ===== 实现 =====

    /** 断言指定源文件（去除注释后）不包含任何禁止出现的类型/标识。 */
    private static void assertSourceDoesNotContain(Path file, String... forbidden) throws IOException {
        String content = stripComments(Files.readString(file));
        List<String> hits = new ArrayList<>();
        for (String token : forbidden) {
            if (content.contains(token)) {
                hits.add(token);
            }
        }
        assertTrue(hits.isEmpty(), file.getFileName() + " must not reference: " + hits);
    }

    /**
     * 断言目录下所有源文件对项目内部包的全部引用都落在白名单前缀内。
     *
     * <p>扫描的是"去除注释后的全文"而非仅 {@code import} 行：
     * 这样通配 import 与内联全限定名（FQN）都无法绕过。
     */
    private static void assertOnlyAllowedImports(Path packageDir, Set<String> allowedPrefixes) throws IOException {
        assertTrue(Files.isDirectory(packageDir), "package directory must exist: " + packageDir);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(packageDir)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = stripComments(Files.readString(file));
                int from = 0;
                while (true) {
                    int idx = content.indexOf(PROJECT_PREFIX, from);
                    if (idx < 0) {
                        break;
                    }
                    String tail = content.substring(idx);
                    boolean allowed = allowedPrefixes.stream().anyMatch(tail::startsWith);
                    if (!allowed) {
                        int end = idx;
                        while (end < content.length()
                                && (Character.isLetterOrDigit(content.charAt(end)) || content.charAt(end) == '.')) {
                            end++;
                        }
                        violations.add(file.getFileName() + " -> " + content.substring(idx, end));
                    }
                    from = idx + PROJECT_PREFIX.length();
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Layer must not depend on packages outside its allow-list. Violations: " + violations);
    }

    /** 去除块注释与行注释，避免 Javadoc 中的 {@code {@link ...}} 造成误判。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < source.length()) {
                        out.append(source.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < source.length()) {
                        out.append(source.charAt(i + 1));
                        i++;
                    }
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
