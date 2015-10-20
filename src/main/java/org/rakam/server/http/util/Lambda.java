package org.rakam.server.http.util;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static java.lang.invoke.MethodHandles.lookup;

public class Lambda {
    private static String REQUEST_HANDLER_ERROR_MESSAGE = "Request handler method %s.%s couldn't converted to request handler lambda expression %s";
    private static String REQUEST_HANDLER_PRIVATE_ERROR_MESSAGE = "Request handler method %s.%s is not accessible: %s";

    public static <T> T produceLambda(final Method sourceMethod, final Method targetMethod) throws Throwable {
        MethodHandles.Lookup lookup = lookup();
        final MethodHandles.Lookup caller = lookup.in(sourceMethod.getDeclaringClass());
        final MethodHandle implementationMethod = caller.unreflect(sourceMethod);

        final MethodType factoryMethodType = MethodType.methodType(targetMethod.getDeclaringClass());

        final Class<?> methodReturn = targetMethod.getReturnType();
        final Class<?>[] methodParams = targetMethod.getParameterTypes();

        final MethodType functionMethodType = MethodType.methodType(methodReturn, methodParams);

        final CallSite lambdaFactory = LambdaMetafactory.metafactory(
                lookup,
                targetMethod.getName(),
                factoryMethodType,
                functionMethodType,
                implementationMethod,
                implementationMethod.type()
        );

        final MethodHandle factoryInvoker = lambdaFactory.getTarget();

        final T lambda = (T) factoryInvoker.invoke();

        return lambda;
    }
}
