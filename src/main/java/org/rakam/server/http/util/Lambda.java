package org.rakam.server.http.util;

import com.google.common.base.Throwables;
import org.rakam.server.http.HttpService;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.invoke.MethodHandles.lookup;

public class Lambda {
    private final static Method biConsumerAccept;
    private final static Method consumerAccept;
    private final static Method functionApply;
    private final static Method biFunctionApply;

    static {
        try {
            biConsumerAccept = BiConsumer.class.getMethod("accept", Object.class, Object.class);
            consumerAccept = Consumer.class.getMethod("accept", Object.class);
            functionApply = Function.class.getMethod("apply", Object.class);
            biFunctionApply = BiFunction.class.getMethod("apply", Object.class, Object.class);
        } catch (NoSuchMethodException e) {
            throw Throwables.propagate(e);
        }
    }

    public static <T> T produceLambdaForConsumer(final Method sourceMethod) {
        return produceLambda(sourceMethod, consumerAccept);
    }

    public static BiFunction produceLambdaForBiFunction(Method method) {
        return produceLambda(method, biFunctionApply);
    }

    public static BiConsumer produceLambdaForBiConsumer(final Method sourceMethod) {
        return produceLambda(sourceMethod, biConsumerAccept);
    }

    public static Function produceLambdaForFunction(final Method sourceMethod) {
        return produceLambda(sourceMethod, functionApply);
    }

    public static <T> T produceLambda(final Method sourceMethod, final Method targetMethod) {
        MethodHandles.Lookup lookup = lookup();
        sourceMethod.setAccessible(true);
        final MethodHandles.Lookup caller = lookup.in(sourceMethod.getDeclaringClass());
        final MethodHandle implementationMethod;

        try {
            implementationMethod = caller.unreflect(sourceMethod);
        } catch (IllegalAccessException e) {
           throw Throwables.propagate(e);
        }

        final MethodType factoryMethodType = MethodType.methodType(targetMethod.getDeclaringClass());

        final Class<?> methodReturn = targetMethod.getReturnType();
        final Class<?>[] methodParams = targetMethod.getParameterTypes();

        final MethodType functionMethodType = MethodType.methodType(methodReturn, methodParams);

        final CallSite lambdaFactory;
        try {
            lambdaFactory = LambdaMetafactory.metafactory(
                    lookup,
                    targetMethod.getName(),
                    factoryMethodType,
                    functionMethodType,
                    implementationMethod,
                    implementationMethod.type()
            );

            final MethodHandle factoryInvoker = lambdaFactory.getTarget();

            return (T) factoryInvoker.invoke();
        } catch (Throwable e) {
            // TODO: fallback to classic reflection method if lambda generation fails.
            throw new InternalError(String.format("Unable to generate lambda for method %s. %s",
                    sourceMethod.getDeclaringClass().getName() + "." + sourceMethod.getName(),
                    e.getMessage()));
        }
    }
}
