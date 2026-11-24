package com.sondertara.joya.core.query;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 惰性列元数据提供器（在 DSL 构建期仅持有类型与属性符号，不分配表别名、不拼接 SQL）。
 *
 * <p>方法引用统一复用 {@link LambdaUtils#resolve(IGetter)} 的 {@code ClassValue} 缓存，
 * 外部字符串标识符采用零分配词法字符校验，最终在 {@link TableScope} 中解析为结构化 {@link SqlNode}。
 */
public sealed interface ColumnMetaProvider
        permits ColumnMetaProvider.GetterColumnMetaProvider,
                ColumnMetaProvider.NameColumnMetaProvider,
                ColumnMetaProvider.TrustedColumnMetaProvider,
                ColumnMetaProvider.ExprColumnMetaProvider {

    /** 合法的列或字段标识符正则（保留作契约常量；高频运行路径走零分配 {@link #isSafeIdentifier}）。 */
    Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /** 合法的表别名或列别名正则。 */
    Pattern SAFE_ALIAS = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Nullable
    Class<?> entityClass();

    default @Nullable String tableAlias() {
        return null;
    }

    @Nullable
    String propertyName();

    default @Nullable Class<?> valueType() {
        return null;
    }

    String canonicalKey();

    /** 解析为完成表归属校验的规范列（非单列表达式抛 {@link UnsupportedOperationException}）。 */
    TableScope.ResolvedColumn resolveColumn(TableScope scope);

    /** 解析为结构化 AST 节点（单列产出 {@link SqlNode.Column}，聚合/原生表达式产出 {@link SqlNode.Func}/{@link SqlNode.Raw}）。 */
    default SqlNode resolveNode(TableScope scope) {
        return resolveColumn(scope).toNode();
    }

    /** 零分配校验纯别名标识符 {@code [A-Za-z_][A-Za-z0-9_]*}。 */
    static boolean isSafeAlias(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return isSimpleIdentRange(text, 0, text.length());
    }

    /** 零分配校验列/字段标识符 {@code [A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?}。 */
    static boolean isSafeIdentifier(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int dot = text.indexOf('.');
        if (dot < 0) {
            return isSimpleIdentRange(text, 0, text.length());
        }
        if (text.indexOf('.', dot + 1) >= 0) {
            return false;
        }
        return isSimpleIdentRange(text, 0, dot) && isSimpleIdentRange(text, dot + 1, text.length());
    }

    private static boolean isSimpleIdentRange(String s, int start, int end) {
        if (start >= end) {
            return false;
        }
        char first = s.charAt(start);
        if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_')) {
            return false;
        }
        for (int i = start + 1; i < end; i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** 从 {@link IGetter}（方法引用或 {@link Expr}）构造列元数据提供器。 */
    static ColumnMetaProvider fromGetter(IGetter<?, ?> getter) {
        Objects.requireNonNull(getter, "column getter must not be null");
        if (getter instanceof Expr<?> expr) {
            return new ExprColumnMetaProvider(expr);
        }
        LambdaUtils.LambdaMeta meta = LambdaUtils.resolve(getter);
        if (meta.implMethodName().startsWith("lambda$")) {
            throw new IllegalArgumentException(
                    "Synthetic lambda is not supported as a column reference; use an entity getter method reference "
                            + "(e.g. User::getName) or Exprs.* instead");
        }
        return new GetterColumnMetaProvider(meta.receiverClass(), meta.propertyName(), meta.returnType());
    }

    /** 从外部传入的字段名或列名构造列元数据提供器（构造期即拦截非法 SQL 注入标识符）。 */
    static ColumnMetaProvider fromName(String fieldOrColumn) {
        if (Strings.isBlank(fieldOrColumn)) {
            throw new IllegalArgumentException(
                    "Invalid column or field identifier (potential SQL injection rejected): " + fieldOrColumn);
        }
        String trimmed = fieldOrColumn.trim();
        if (!isSafeIdentifier(trimmed)) {
            throw new IllegalArgumentException(
                    "Invalid column or field identifier (potential SQL injection rejected): " + fieldOrColumn);
        }
        return new NameColumnMetaProvider(trimmed);
    }

    /** 从框架内部已通过 {@code EntityMetadataContext} 验证的主表规范列名构造提供器（零正则开销）。 */
    static ColumnMetaProvider ofTrustedColumn(String canonicalColumn) {
        return new TrustedColumnMetaProvider(Objects.requireNonNull(canonicalColumn, "canonicalColumn must not be null"));
    }

    record GetterColumnMetaProvider(
            Class<?> entityClass,
            String propertyName,
            Class<?> valueType
    ) implements ColumnMetaProvider {

        public GetterColumnMetaProvider {
            Objects.requireNonNull(entityClass, "entityClass must not be null");
            Objects.requireNonNull(propertyName, "propertyName must not be null");
            Objects.requireNonNull(valueType, "valueType must not be null");
        }

        @Override
        public String canonicalKey() {
            return entityClass.getName() + "#" + propertyName;
        }

        @Override
        public TableScope.ResolvedColumn resolveColumn(TableScope scope) {
            return scope.resolveGetterColumn(entityClass, propertyName);
        }
    }

    record NameColumnMetaProvider(String identifier) implements ColumnMetaProvider {

        public NameColumnMetaProvider {
            Objects.requireNonNull(identifier, "identifier must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return null;
        }

        @Override
        public String propertyName() {
            int dot = identifier.indexOf('.');
            return dot >= 0 ? identifier.substring(dot + 1) : identifier;
        }

        @Override
        public String canonicalKey() {
            return "name:" + identifier;
        }

        @Override
        public TableScope.ResolvedColumn resolveColumn(TableScope scope) {
            return scope.resolveIdentifierColumn(identifier);
        }
    }

    record TrustedColumnMetaProvider(String canonicalColumn) implements ColumnMetaProvider {

        public TrustedColumnMetaProvider {
            Objects.requireNonNull(canonicalColumn, "canonicalColumn must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return null;
        }

        @Override
        public String propertyName() {
            return canonicalColumn;
        }

        @Override
        public String canonicalKey() {
            return "trusted:" + canonicalColumn;
        }

        @Override
        public TableScope.ResolvedColumn resolveColumn(TableScope scope) {
            return scope.resolveTrustedColumn(canonicalColumn);
        }
    }

    record ExprColumnMetaProvider(Expr<?> expr) implements ColumnMetaProvider {

        public ExprColumnMetaProvider {
            Objects.requireNonNull(expr, "expr must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return expr instanceof Expr.ColumnExpr<?> col ? col.entityClass() : null;
        }

        @Override
        public @Nullable String tableAlias() {
            return expr instanceof Expr.ColumnExpr<?> col ? col.tableAlias() : null;
        }

        @Override
        public @Nullable String propertyName() {
            return expr.alias();
        }

        @Override
        public @Nullable Class<?> valueType() {
            return expr.resultType();
        }

        @Override
        public String canonicalKey() {
            if (expr instanceof Expr.ColumnExpr<?> col) {
                String prefix = col.tableAlias() != null ? col.tableAlias() + "@" : "";
                return prefix + col.entityClass().getName() + "#" + col.propertyName();
            }
            return "expr:" + System.identityHashCode(expr);
        }

        @Override
        public TableScope.ResolvedColumn resolveColumn(TableScope scope) {
            if (expr instanceof Expr.ColumnExpr<?> col) {
                return col.resolveColumn(scope);
            }
            throw new UnsupportedOperationException(
                    "Non-column SQL expression cannot be used as a table join key: " + expr);
        }

        @Override
        public SqlNode resolveNode(TableScope scope) {
            return expr.toNode(scope);
        }
    }
}
