package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.statement.factory.SqlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code JoyaTemplate} 公开签名不得暴露 {@code PageResult} / {@code PageRequest}。</li>
 *   <li>"INSERT INTO / UPDATE ... SET / DELETE FROM" 只允许 {@link SqlRenderer} 拼接。</li>
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
    void joyaTemplatePublicSignaturesMustNotExposePageResultOrPageRequest() {
        for (Method method : JoyaTemplate.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String returnType = method.getReturnType().getName();
            assertTrue(!returnType.contains("PageResult") && !returnType.contains("PageRequest"),
                    "JoyaTemplate public method must not return PageResult/PageRequest: " + method);
            for (Class<?> paramType : method.getParameterTypes()) {
                String paramName = paramType.getName();
                assertTrue(!paramName.contains("PageResult") && !paramName.contains("PageRequest"),
                        "JoyaTemplate public method must not accept PageResult/PageRequest: " + method);
            }
        }
    }

    @Test
    void dmlTextMustOnlyBeBuiltInsideSqlRenderer() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(resolveMainJavaRoot())) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = stripComments(Files.readString(file));
                if (file.getFileName().toString().equals("SqlRenderer.java")) {
                    continue;
                }
                if (content.contains("append(\"INSERT INTO ")
                        || content.contains("\"INSERT INTO \" +")
                        || content.contains("append(\"UPDATE \")")
                        || content.contains("\"UPDATE \" +")
                        || content.contains("append(\"DELETE FROM ")
                        || content.contains("\"DELETE FROM \" +")) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), violations,
                "Only " + SqlRenderer.class.getSimpleName() + ".java may assemble DML text; violations: " + violations);
    }

    @Test
    void batchPlannerMustStayWriteOnly() throws IOException {
        assertSourceDoesNotContain(corePackage("BatchPlanner.java"),
                "Query<", "SelectStatementFactory", "DeleteStatementFactory", "EntityFinder", "EntityDeleter");
    }

    @Test
    void readAndDeleteCollaboratorsMustNotDependOnBatchPlanner() throws IOException {
        assertSourceDoesNotContain(corePackage("EntityFinder.java"), "BatchPlanner");
        assertSourceDoesNotContain(corePackage("EntityDeleter.java"), "BatchPlanner");
    }

    @Test
    void entityOpsFacadeMustNotAssembleStatements() throws IOException {
        assertSourceDoesNotContain(corePackage("EntityOps.java"), "SqlStatement", "BatchSqlStatement");
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
