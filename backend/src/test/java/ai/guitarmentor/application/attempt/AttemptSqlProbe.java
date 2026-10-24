package ai.guitarmentor.application.attempt;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Test-only JDBC statement probe. Wraps the actual DataSource, so both Hibernate
 * and JdbcTemplate are measured. Captures calls on the requesting test thread,
 * not unrelated application background workers.
 */
final class AttemptSqlProbe implements BeanPostProcessor {
    private static final ThreadLocal<List<String>> CAPTURE = new ThreadLocal<>();

    static List<String> capture(Runnable operation) {
        if (CAPTURE.get() != null) throw new IllegalStateException("Nested SQL capture");
        List<String> statements = new ArrayList<>();
        CAPTURE.set(statements);
        try {
            operation.run();
            return List.copyOf(statements);
        } finally {
            CAPTURE.remove();
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource delegate) || Proxy.isProxyClass(bean.getClass())) {
            return bean;
        }
        return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (result instanceof Connection connection
                            && method.getName().equals("getConnection")) {
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (connectionProxy, call, values) -> {
                                    if (call.getName().equals("prepareStatement")
                                            && values != null && values.length > 0
                                            && values[0] instanceof String sql && CAPTURE.get() != null) {
                                        CAPTURE.get().add(sql.toLowerCase(Locale.ROOT));
                                    }
                                    return invoke(connection, call, values);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object receiver, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
