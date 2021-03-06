/*
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.vertx.web;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.WithAnnotations;

import org.jboss.weld.util.reflection.HierarchyDiscovery;
import org.jboss.weld.util.reflection.Reflections;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * This extensions allows to register {@link Route} handlers discovered during container initialization.
 *
 * @author Martin Kouba
 * @see WebRoute
 */
public class RouteExtension implements Extension {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteExtension.class.getName());

    private final List<AnnotatedType<? extends Handler<RoutingContext>>> handlerTypes = new LinkedList<>();

    private final List<HandlerInstance<?>> handlerInstances = new LinkedList<>();

    private BeanManager beanManager;

    // Implementation note - ProcessAnnotatedType<? extends Handler<RoutingContext>> is more correct but prevents Weld from using
    // FastProcessAnnotatedTypeResolver
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void processHandlerAnnotatedType(@Observes @WithAnnotations({ WebRoute.class, WebRoutes.class }) ProcessAnnotatedType<? extends Handler> event) {
        AnnotatedType<? extends Handler> annotatedType = event.getAnnotatedType();
        // Double check the handler type
        if (isWebRoute(annotatedType) && isRouteHandler(annotatedType)) {
            LOGGER.debug("Route handler found: {0}", annotatedType);
            // At this point it is safe to cast the annotated type
            handlerTypes.add((AnnotatedType<? extends Handler<RoutingContext>>) annotatedType);
        }
    }

    void afterDeploymentValidation(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    void beforeShutdown(@Observes BeforeShutdown event) {
        for (HandlerInstance<?> handler : handlerInstances) {
            handler.dispose();
        }
        handlerInstances.clear();
        handlerTypes.clear();
    }

    public void registerRoutes(Router router) {
        for (AnnotatedType<? extends Handler<RoutingContext>> annotatedType : handlerTypes) {
            processHandlerType(annotatedType, router);
        }
    }

    private void processHandlerType(AnnotatedType<? extends Handler<RoutingContext>> annotatedType, Router router) {
        WebRoute[] webRoutes = getWebRoutes(annotatedType);
        if (webRoutes.length == 0) {
            LOGGER.warn("No @WebRoute annotation found on {0}", annotatedType);
            return;
        }
        HandlerInstance<?> handlerInstance = new HandlerInstance<>(annotatedType, beanManager);
        handlerInstances.add(handlerInstance);
        Handler<RoutingContext> handler = handlerInstance.instance;
        for (WebRoute webRoute : webRoutes) {
            addRoute(router, handler, webRoute);
        }
    }

    private void addRoute(Router router, Handler<RoutingContext> handler, WebRoute webRoute) {
        Route route;
        if (!webRoute.regex().isEmpty()) {
            route = router.routeWithRegex(webRoute.regex());
        } else if (!webRoute.value().isEmpty()) {
            route = router.route(webRoute.value());
        } else {
            route = router.route();
        }
        if (webRoute.methods().length > 0) {
            for (HttpMethod method : webRoute.methods()) {
                route.method(method);
            }
        }
        if (webRoute.order() != Integer.MIN_VALUE) {
            route.order(webRoute.order());
        }
        if (webRoute.produces().length > 0) {
            for (String produces : webRoute.produces()) {
                route.produces(produces);
            }
        }
        if (webRoute.consumes().length > 0) {
            for (String consumes : webRoute.consumes()) {
                route.consumes(consumes);
            }
        }
        switch (webRoute.type()) {
            case NORMAL:
                route.handler(handler);
                break;
            case BLOCKING:
                // We don't mind if blocking handlers are executed in parallel
                route.blockingHandler(handler, false);
                break;
            case FAILURE:
                route.failureHandler(handler);
                break;
            default:
                throw new IllegalStateException("Unsupported handler type: " + webRoute.type());
        }
        LOGGER.debug("Route registered for {0}", webRoute);
    }

    private WebRoute[] getWebRoutes(Annotated annotated) {
        WebRoute webRoute = annotated.getAnnotation(WebRoute.class);
        if (webRoute != null) {
            return new WebRoute[] { webRoute };
        }
        Annotation container = annotated.getAnnotation(WebRoutes.class);
        if (container != null) {
            WebRoutes webRoutes = (WebRoutes) container;
            return webRoutes.value();
        }
        return new WebRoute[] {};
    }

    private boolean isWebRoute(Annotated annotated) {
        return annotated.isAnnotationPresent(WebRoute.class) || annotated.isAnnotationPresent(WebRoutes.class);
    }

    private boolean isRouteHandler(AnnotatedType<?> annotatedType) {
        if (!Reflections.isTopLevelOrStaticNestedClass(annotatedType.getJavaClass())) {
            LOGGER.warn("Ignoring {0} - class annotated with @WebRoute must be top-level or static nested class", annotatedType.getJavaClass());
            return false;
        }
        Set<Type> types = new HierarchyDiscovery(annotatedType.getBaseType()).getTypeClosure();
        for (Type type : types) {
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                if (parameterizedType.getRawType().equals(Handler.class)) {
                    Type[] arguments = parameterizedType.getActualTypeArguments();
                    if (arguments.length == 1 && arguments[0].equals(RoutingContext.class)) {
                        return true;
                    }
                }
            }
        }
        LOGGER.warn("Ignoring {0} - class annotated with @WebRoute must implement io.vertx.core.Handler<RoutingContext>", annotatedType.getJavaClass());
        return false;
    }

    class HandlerInstance<T extends Handler<RoutingContext>> {

        private final AnnotatedType<T> annotatedType;

        private final CreationalContext<T> creationalContext;

        private final InjectionTarget<T> injectionTarget;

        private final T instance;

        HandlerInstance(AnnotatedType<T> annotatedType, BeanManager beanManager) {
            this.annotatedType = annotatedType;
            this.injectionTarget = beanManager.getInjectionTargetFactory(annotatedType).createInjectionTarget(null);
            this.creationalContext = beanManager.createCreationalContext(null);
            this.instance = injectionTarget.produce(creationalContext);
            injectionTarget.inject(instance, creationalContext);
            injectionTarget.postConstruct(instance);
        }

        private void dispose() {
            try {
                injectionTarget.preDestroy(instance);
                injectionTarget.dispose(instance);
                creationalContext.release();
            } catch (Exception e) {
                LOGGER.error("Error disposing a route handler for {0}", e, annotatedType);
            }
        }

    }

}
