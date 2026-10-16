package com.learnhub.assistant.runtime;

import java.sql.Connection;
import java.sql.Statement;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class AssistantQueryInterceptor implements Interceptor {
    @Override public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        var scope = AssistantIoScope.current();
        if (scope != null && result instanceof Statement statement) scope.track(statement);
        return result;
    }
}
