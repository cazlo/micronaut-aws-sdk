package com.agorapulse.micronaut.aws.dynamodb;

import com.agorapulse.micronaut.aws.dynamodb.annotation.*;
import com.agorapulse.micronaut.aws.dynamodb.builder.Builders;
import com.agorapulse.micronaut.aws.dynamodb.builder.DetachedCriteria;
import com.agorapulse.micronaut.aws.dynamodb.builder.DetachedUpdate;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.IDynamoDBMapper;
import groovy.lang.Closure;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableArgumentValue;

import javax.inject.Singleton;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Function;

@Singleton
public class ServiceIntroduction implements MethodInterceptor<Object, Object> {

    private static final String HASH = "hash";
    private static final String RANGE = "range";

    private static class HashAndRange {
        Argument<?> hashKey;
        Argument<?> rangeKey;

        boolean isValid() {
            return hashKey != null;
        }

    }

    private final BeanContext beanContext;
    private final IDynamoDBMapper mapper;
    private final AmazonDynamoDB amazonDynamoDB;
    private final DynamoDBServiceFactory dynamoDBServiceFactory;

    public ServiceIntroduction(BeanContext beanContext, IDynamoDBMapper mapper, AmazonDynamoDB amazonDynamoDB, DynamoDBServiceFactory dynamoDBServiceFactory) {
        this.beanContext = beanContext;
        this.mapper = mapper;
        this.amazonDynamoDB = amazonDynamoDB;
        this.dynamoDBServiceFactory = dynamoDBServiceFactory;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        AnnotationValue<Service> serviceAnnotationValue = context.getAnnotation(Service.class);

        if (serviceAnnotationValue == null) {
            throw new IllegalStateException("Invocation context is missing required annotation Service");
        }

        String methodName = context.getMethodName();
        Class type = serviceAnnotationValue.getValue(Class.class).orElseThrow(() -> new IllegalArgumentException("Annotation is missing the type value!"));
        DynamoDBService service = dynamoDBServiceFactory.findOrCreate(type);

        if (methodName.startsWith("save")) {
            return handleSave(service, context);
        }

        if (methodName.startsWith("get") || methodName.startsWith("load")) {
            return handleGet(service, context);
        }


        // TODO: change to annotation value when fixed
        // https://github.com/micronaut-projects/micronaut-core/issues/1022
        if (context.getTargetMethod().isAnnotationPresent(Query.class)) {
            DetachedCriteria criteria = evaluateAnnotationType(context.getTargetMethod().getAnnotation(Query.class).value(), context);

            if (methodName.startsWith("count")) {
                return criteria.count(mapper);
            }

            if (methodName.startsWith("delete")) {
                return service.deleteAllByConditions(criteria.resolveExpression(mapper), Collections.emptyMap());
            }

            return criteria.query(mapper);
        }

        if (context.getTargetMethod().isAnnotationPresent(Update.class)) {
            DetachedUpdate criteria = evaluateAnnotationType(context.getTargetMethod().getAnnotation(Update.class).value(), context);

            return criteria.update(mapper, amazonDynamoDB);
        }

        if (methodName.startsWith("count")) {
            return simpleHashAndRangeQuery(type, context).count(mapper);
        }

        if (methodName.startsWith("delete")) {
            return handleDelete(type, service, context);
        }

        if (methodName.startsWith("query") || methodName.startsWith("findAll") || methodName.startsWith("list")) {
            return simpleHashAndRangeQuery(type, context).query(mapper);
        }

        throw new UnsupportedOperationException("Cannot implement method " + context.getExecutableMethod());
    }

    private <T> T evaluateAnnotationType(Class<? extends Function<Map<String, Object>, T>> updateDefinitionType, MethodInvocationContext<Object, Object> context) {
        Map<String, Object> parameterValueMap = context.getParameterValueMap();

        if (Closure.class.isAssignableFrom(updateDefinitionType)) {
            try {
                Closure<T> closure = (Closure<T>) updateDefinitionType.getConstructor(Object.class, Object.class).newInstance(parameterValueMap, parameterValueMap);
                closure.setDelegate(parameterValueMap);
                closure.setResolveStrategy(Closure.DELEGATE_FIRST);
                return closure.call(parameterValueMap);
            } catch (InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalArgumentException("Cannot instantiate closure! Type: " + updateDefinitionType, e);
            }
        }

        try {
            return updateDefinitionType.newInstance().apply(parameterValueMap);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot instantiate function! Type: " + updateDefinitionType, e);
        }

    }

    private Object handleSave(DynamoDBService service, MethodInvocationContext<Object, Object> context) {
        Map<String, MutableArgumentValue<?>> params = context.getParameters();
        Argument[] args = context.getArguments();

        if (args.length > 1) {
            throw new UnsupportedOperationException("Method expects at most 1 parameters - item, iterable of items or array of items");
        }

        Argument itemArgument = args[0];
        Object item = params.get(itemArgument.getName()).getValue();

        if (itemArgument.getType().isArray()) {
            return service.saveAll(Arrays.asList((Object[]) item));
        }

        if (Iterable.class.isAssignableFrom(itemArgument.getType())) {
            return service.saveAll(toList((Iterable) item));
        }

        return service.save(item);
    }

    private Object handleDelete(Class type, DynamoDBService service, MethodInvocationContext<Object, Object> context) {
        Map<String, MutableArgumentValue<?>> params = context.getParameters();
        Argument[] args = context.getArguments();

        if (args.length == 1) {
            Argument itemArgument = args[0];
            Object item = params.get(itemArgument.getName()).getValue();

            if (itemArgument.getType().isArray() && type.isAssignableFrom(itemArgument.getType().getComponentType())) {
                service.deleteAll(Arrays.asList((Object[]) item));
                return null;
            }

            if (Iterable.class.isAssignableFrom(itemArgument.getType()) && type.isAssignableFrom(itemArgument.getTypeParameters()[0].getType())) {
                service.deleteAll(toList((Iterable) item));
                return null;
            }

            if (type.isAssignableFrom(itemArgument.getType())) {
                service.delete(item);
                return null;
            }
        }

        if (args.length > 2) {
            throw new UnsupportedOperationException("Method expects at most 2 parameters - hash key and range key, an item, iterable of items or an array of items");
        }

        HashAndRange hashAndRange = findHashAndRange(args);
        Object hashKey = params.get(hashAndRange.hashKey.getName()).getValue();

        if (hashAndRange.rangeKey == null) {
            service.deleteByHash(hashKey);
            return null;
        }

        Object rangeKey = params.get(hashAndRange.rangeKey.getName()).getValue();
        service.delete(hashKey, rangeKey);
        
        return null;
    }

    private Object handleGet(DynamoDBService service, MethodInvocationContext<Object, Object> context) {
        Map<String, MutableArgumentValue<?>> params = context.getParameters();
        Argument[] args = context.getArguments();

        if (args.length > 2) {
            throw new UnsupportedOperationException("Method expects at most 2 parameters - hash key and range key");
        }

        HashAndRange hashAndRange = findHashAndRange(args);
        Object hashKey = params.get(hashAndRange.hashKey.getName()).getValue();

        if (hashAndRange.rangeKey == null) {
            return service.get(hashKey);
        }

        Object rangeKey = params.get(hashAndRange.rangeKey.getName()).getValue();

        if (hashAndRange.rangeKey.getType().isArray()) {
            return service.getAll(hashKey, Arrays.asList((Object[]) rangeKey));
        }

        if (Iterable.class.isAssignableFrom(hashAndRange.rangeKey.getType())) {
            return service.getAll(hashKey, toList((Iterable) rangeKey));
        }

        return service.get(hashKey, rangeKey);
    }

    private DetachedCriteria simpleHashAndRangeQuery(
        Class type,
        MethodInvocationContext<Object, Object> context
    ) {
        Map<String, MutableArgumentValue<?>> params = context.getParameters();
        Argument[] args = context.getArguments();

        if (args.length > 2) {
            throw new UnsupportedOperationException("Method expects at most 2 parameters - hash key and optional range key");
        }
        HashAndRange hashAndRange = findHashAndRange(args);
        Object hashKey = params.get(hashAndRange.hashKey.getName()).getValue();

        if (hashAndRange.rangeKey == null) {
            return Builders.query(type, q -> q.hash(hashKey));
        }

        Object rangeKey = params.get(hashAndRange.rangeKey.getName()).getValue();
        return Builders.query(type, q -> q.hash(hashKey).range(r -> r.eq(rangeKey)));
    }


    private HashAndRange findHashAndRange(Argument[] arguments) {
        HashAndRange names = new HashAndRange();
        for (Argument<?> argument : arguments) {
            if (argument.isAnnotationPresent(RangeKey.class) || argument.getName().toLowerCase().contains(RANGE)) {
                names.rangeKey = argument;
            } else if (argument.isAnnotationPresent(HashKey.class) || argument.getName().toLowerCase().contains(HASH)) {
                names.hashKey = argument;
            }
        }

        if (!names.isValid()) {
            throw new UnsupportedOperationException("Method needs to have at least one argument annotated with @DynamoDBHashKey or with called 'hash'");
        }

        return names;
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        if (iterable instanceof List) {
            return (List<T>) iterable;
        }

        List<T> ret = new ArrayList<>();
        iterable.forEach(ret::add);
        return ret;
    }
}
