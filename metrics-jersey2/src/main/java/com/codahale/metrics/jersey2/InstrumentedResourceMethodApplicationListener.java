package com.codahale.metrics.jersey2;

import com.codahale.metrics.Timer;
import com.codahale.metrics.*;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.*;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.model.*;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import javax.ws.rs.core.Configuration;
import javax.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.codahale.metrics.MetricRegistry.name;
import static com.codahale.metrics.annotation.ResponseMeteredLevel.*;

/**
 * An application event listener that listens for Jersey application initialization to
 * be finished, then creates a map of resource method that have metrics annotations.
 * <p>
 * Finally, it listens for method start events, and returns a {@link RequestEventListener}
 * that updates the relevant metric for suitably annotated methods when it gets the
 * request events indicating that the method is about to be invoked, or just got done
 * being invoked.
 */
@Provider
public class InstrumentedResourceMethodApplicationListener implements ApplicationEventListener, ModelProcessor {

    private static final String[] REQUEST_FILTERING = {"request", "filtering"};
    private static final String[] RESPONSE_FILTERING = {"response", "filtering"};
    private static final String TOTAL = "total";

    private final MetricRegistry metrics;
    private final ConcurrentMap<EventTypeAndMethod, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, Meter> meters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters = new ConcurrentHashMap<>();

    private final Clock clock;
    private final boolean trackFilters;
    private final Supplier<Reservoir> reservoirSupplier;

    /**
     * Construct an application event listener using the given metrics registry.
     * <p>
     * When using this constructor, the {@link InstrumentedResourceMethodApplicationListener}
     * should be added to a Jersey {@code ResourceConfig} as a singleton.
     *
     * @param metrics a {@link MetricRegistry}
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics) {
        this(metrics, Clock.defaultClock(), false);
    }

    /**
     * Constructs a custom application listener.
     *
     * @param metrics      the metrics registry where the metrics will be stored
     * @param clock        the {@link Clock} to track time (used mostly in testing) in timers
     * @param trackFilters whether the processing time for request and response filters should be tracked
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics, final Clock clock,
                                                         final boolean trackFilters) {
        this(metrics, clock, trackFilters, ExponentiallyDecayingReservoir::new);
    }

    /**
     * Constructs a custom application listener.
     *
     * @param metrics           the metrics registry where the metrics will be stored
     * @param clock             the {@link Clock} to track time (used mostly in testing) in timers
     * @param trackFilters      whether the processing time for request and response filters should be tracked
     * @param reservoirSupplier Supplier for creating the {@link Reservoir} for {@link Timer timers}.
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics, final Clock clock,
                                                         final boolean trackFilters,
                                                         final Supplier<Reservoir> reservoirSupplier) {
        this.metrics = metrics;
        this.clock = clock;
        this.trackFilters = trackFilters;
        this.reservoirSupplier = reservoirSupplier;
    }

    /**
     * A private class to maintain the metric for a method annotated with the
     * {@link ExceptionMetered} annotation, which needs to maintain both a meter
     * and a cause for which the meter should be updated.
     */
    private static class ExceptionMeterMetric {
        public final Meter meter;
        public final Class<? extends Throwable> cause;

        public ExceptionMeterMetric(final MetricRegistry registry,
                                    final ResourceMethod method,
                                    final ExceptionMetered exceptionMetered) {
            final String name = chooseName(exceptionMetered.name(),
                    exceptionMetered.absolute(), method, ExceptionMetered.class, ExceptionMetered.DEFAULT_NAME_SUFFIX);
            this.meter = registry.meter(name);
            this.cause = exceptionMetered.cause();
        }
    }

    /**
     * A private class to maintain the metrics for a method annotated with the
     * {@link ResponseMetered} annotation, which needs to maintain meters for
     * different response codes
     */
    private static class ResponseMeterMetric {
        private static final Set<ResponseMeteredLevel> COARSE_METER_LEVELS = EnumSet.of(COARSE, ALL);
        private static final Set<ResponseMeteredLevel> DETAILED_METER_LEVELS = EnumSet.of(DETAILED, ALL);
        private final List<Meter> meters;
        private final Map<Integer, Meter> responseCodeMeters;
        private final MetricRegistry metricRegistry;
        private final String metricName;
        private final ResponseMeteredLevel level;

        public ResponseMeterMetric(final MetricRegistry registry,
                                   final ResourceMethod method,
                                   final ResponseMetered responseMetered) {
            this.metricName = chooseName(responseMetered.name(), responseMetered.absolute(), method, Metered.class);
            this.level = responseMetered.level();
            this.meters = COARSE_METER_LEVELS.contains(level) ?
                    Collections.unmodifiableList(Arrays.asList(
                    registry.meter(name(metricName, "1xx-responses")), // 1xx
                    registry.meter(name(metricName, "2xx-responses")), // 2xx
                    registry.meter(name(metricName, "3xx-responses")), // 3xx
                    registry.meter(name(metricName, "4xx-responses")), // 4xx
                    registry.meter(name(metricName, "5xx-responses"))  // 5xx
            )) : Collections.emptyList();
            this.responseCodeMeters = DETAILED_METER_LEVELS.contains(level) ? new ConcurrentHashMap<>() : Collections.emptyMap();
            this.metricRegistry = registry;
        }

        public void mark(int statusCode) {
            if (DETAILED_METER_LEVELS.contains(level)) {
                getResponseCodeMeter(statusCode).mark();
            }

            if (COARSE_METER_LEVELS.contains(level)) {
                final int responseStatus = statusCode / 100;
                if (responseStatus >= 1 && responseStatus <= 5) {
                    meters.get(responseStatus - 1).mark();
                }
            }
        }

        private Meter getResponseCodeMeter(int statusCode) {
            return responseCodeMeters
                    .computeIfAbsent(statusCode, sc -> metricRegistry
                            .meter(name(metricName, String.format("%d-responses", sc))));
        }
    }

    private static class TimerRequestEventListener implements RequestEventListener {

        private final ConcurrentMap<EventTypeAndMethod, Timer> timers;
        private final Clock clock;
        private final long start;
        private Timer.Context resourceMethodStartContext;
        private Timer.Context requestMatchedContext;
        private Timer.Context responseFiltersStartContext;

        public TimerRequestEventListener(final ConcurrentMap<EventTypeAndMethod, Timer> timers, final Clock clock) {
            this.timers = timers;
            this.clock = clock;
            start = clock.getTick();
        }

        @Override
        public void onEvent(RequestEvent event) {
            switch (event.getType()) {
                case RESOURCE_METHOD_START:
                    resourceMethodStartContext = context(event);
                    break;
                case REQUEST_MATCHED:
                    requestMatchedContext = context(event);
                    break;
                case RESP_FILTERS_START:
                    responseFiltersStartContext = context(event);
                    break;
                case RESOURCE_METHOD_FINISHED:
                    if (resourceMethodStartContext != null) {
                        resourceMethodStartContext.close();
                    }
                    break;
                case REQUEST_FILTERED:
                    if (requestMatchedContext != null) {
                        requestMatchedContext.close();
                    }
                    break;
                case RESP_FILTERS_FINISHED:
                    if (responseFiltersStartContext != null) {
                        responseFiltersStartContext.close();
                    }
                    break;
                case FINISHED:
                    if (requestMatchedContext != null && responseFiltersStartContext != null) {
                        final Timer timer = timer(event);
                        if (timer != null) {
                            timer.update(clock.getTick() - start, TimeUnit.NANOSECONDS);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private Timer timer(RequestEvent event) {
            final ResourceMethod resourceMethod = event.getUriInfo().getMatchedResourceMethod();

            if (resourceMethod == null) {
                return null;
            }

            final AnnotatedMethod<Timed> annotatedMethod = AnnotatedMethod.get(resourceMethod, Timed.class);

            if (!annotatedMethod.hasAnnotation()) {
                return null;
            }

            return timers.get(
                new EventTypeAndMethod(
                    event.getType(),
                    annotatedMethod.getMethod()
                )
            );
        }

        private Timer.Context context(RequestEvent event) {
            final Timer timer = timer(event);
            return timer != null ? timer.time() : null;
        }
    }

    private static class MeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, Meter> meters;

        public MeterRequestEventListener(final ConcurrentMap<Method, Meter> meters) {
            this.meters = meters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_START) {
                final Meter meter = AnnotatedMethod.get(this.meters, event);

                if (meter != null) {
                    meter.mark();
                }
            }
        }
    }

    private static class ExceptionMeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters;

        public ExceptionMeterRequestEventListener(final ConcurrentMap<Method, ExceptionMeterMetric> exceptionMeters) {
            this.exceptionMeters = exceptionMeters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.ON_EXCEPTION) {
                final ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();

                ExceptionMeterMetric metric = null;

                if (method != null) {
                    final AnnotatedMethod<ExceptionMetered> annotatedMethod = AnnotatedMethod.get(method, ExceptionMetered.class);

                    if (annotatedMethod.hasAnnotation()) {
                        metric = this.exceptionMeters.get(annotatedMethod.getMethod());
                    }
                }

                if (metric != null) {
                    if (metric.cause.isAssignableFrom(event.getException().getClass()) ||
                            (event.getException().getCause() != null &&
                                    metric.cause.isAssignableFrom(event.getException().getCause().getClass()))) {
                        metric.meter.mark();
                    }
                }
            }
        }
    }

    private static class ResponseMeterRequestEventListener implements RequestEventListener {
        private final ConcurrentMap<Method, ResponseMeterMetric> responseMeters;

        public ResponseMeterRequestEventListener(final ConcurrentMap<Method, ResponseMeterMetric> responseMeters) {
            this.responseMeters = responseMeters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.FINISHED) {
                final ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();

                ResponseMeterMetric metric = null;

                if (method != null) {
                    final AnnotatedMethod<ResponseMetered> annotatedMethod = AnnotatedMethod.get(method, ResponseMetered.class);

                    if (annotatedMethod.hasAnnotation()) {
                        metric = this.responseMeters.get(annotatedMethod.getMethod());
                    }
                }

                if (metric != null) {
                    ContainerResponse containerResponse = event.getContainerResponse();
                    if (containerResponse == null && event.getException() != null) {
                        metric.mark(500);
                    } else if (containerResponse != null) {
                        metric.mark(containerResponse.getStatus());
                    }
                }
            }
        }
    }

    private static class ChainedRequestEventListener implements RequestEventListener {
        private final RequestEventListener[] listeners;

        private ChainedRequestEventListener(final RequestEventListener... listeners) {
            this.listeners = listeners;
        }

        @Override
        public void onEvent(final RequestEvent event) {
            for (RequestEventListener listener : listeners) {
                listener.onEvent(event);
            }
        }
    }

    @Override
    public void onEvent(ApplicationEvent event) {
        if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED) {
            registerMetricsForModel(event.getResourceModel());
        }
    }

    @Override
    public ResourceModel processResourceModel(ResourceModel resourceModel, Configuration configuration) {
        return resourceModel;
    }

    @Override
    public ResourceModel processSubResource(ResourceModel subResourceModel, Configuration configuration) {
        registerMetricsForModel(subResourceModel);
        return subResourceModel;
    }

    private void registerMetricsForModel(ResourceModel resourceModel) {
        for (final Resource resource : resourceModel.getResources()) {

            final Timed classLevelTimed = getClassLevelAnnotation(resource, Timed.class);
            final Metered classLevelMetered = getClassLevelAnnotation(resource, Metered.class);
            final ExceptionMetered classLevelExceptionMetered = getClassLevelAnnotation(resource, ExceptionMetered.class);
            final ResponseMetered classLevelResponseMetered = getClassLevelAnnotation(resource, ResponseMetered.class);

            for (final ResourceMethod method : resource.getAllMethods()) {
                registerTimedAnnotations(method, classLevelTimed);
                registerMeteredAnnotations(method, classLevelMetered);
                registerExceptionMeteredAnnotations(method, classLevelExceptionMetered);
                registerResponseMeteredAnnotations(method, classLevelResponseMetered);
            }

            for (final Resource childResource : resource.getChildResources()) {

                final Timed classLevelTimedChild = getClassLevelAnnotation(childResource, Timed.class);
                final Metered classLevelMeteredChild = getClassLevelAnnotation(childResource, Metered.class);
                final ExceptionMetered classLevelExceptionMeteredChild = getClassLevelAnnotation(childResource, ExceptionMetered.class);
                final ResponseMetered classLevelResponseMeteredChild = getClassLevelAnnotation(childResource, ResponseMetered.class);

                for (final ResourceMethod method : childResource.getAllMethods()) {
                    registerTimedAnnotations(method, classLevelTimedChild);
                    registerMeteredAnnotations(method, classLevelMeteredChild);
                    registerExceptionMeteredAnnotations(method, classLevelExceptionMeteredChild);
                    registerResponseMeteredAnnotations(method, classLevelResponseMeteredChild);
                }
            }
        }
    }

    @Override
    public RequestEventListener onRequest(final RequestEvent event) {
        final RequestEventListener listener = new ChainedRequestEventListener(
                new TimerRequestEventListener(timers, clock),
                new MeterRequestEventListener(meters),
                new ExceptionMeterRequestEventListener(exceptionMeters),
                new ResponseMeterRequestEventListener(responseMeters));

        return listener;
    }

    private <T extends Annotation> T getClassLevelAnnotation(final Resource resource, final Class<T> annotationClazz) {
        T annotation = null;

        for (final Class<?> clazz : resource.getHandlerClasses()) {
            annotation = clazz.getAnnotation(annotationClazz);

            if (annotation != null) {
                break;
            }
        }
        return annotation;
    }

    private void registerTimedAnnotations(final ResourceMethod method, final Timed classLevelTimed) {
        final AnnotatedMethod<Timed> annotatedMethod = AnnotatedMethod.get(method, Timed.class);

        if (!annotatedMethod.hasMethod()) {
            return;
        }

        if (classLevelTimed != null) {
            registerTimers(
                method,
                annotatedMethod.getMethod(),
                classLevelTimed
            );

            return;
        }

        if (annotatedMethod.hasAnnotation()) {
            registerTimers(
                method,
                annotatedMethod.getMethod(),
                annotatedMethod.getAnnotation()
            );
        }
    }

    private void registerTimers(ResourceMethod method, Method definitionMethod, Timed annotation) {
        timers.putIfAbsent(
            EventTypeAndMethod.requestMethodStart(definitionMethod),
            timerMetric(metrics, method, annotation)
        );

        if (trackFilters) {
            timers.putIfAbsent(EventTypeAndMethod.requestMatched(definitionMethod), timerMetric(metrics, method, annotation, REQUEST_FILTERING));
            timers.putIfAbsent(EventTypeAndMethod.respFiltersStart(definitionMethod), timerMetric(metrics, method, annotation, RESPONSE_FILTERING));
            timers.putIfAbsent(EventTypeAndMethod.finished(definitionMethod), timerMetric(metrics, method, annotation, TOTAL));
        }
    }

    private void registerMeteredAnnotations(final ResourceMethod method, final Metered classLevelMetered) {
        final AnnotatedMethod<Metered> annotatedMethod = AnnotatedMethod.get(method, Metered.class);

        if (!annotatedMethod.hasMethod()) {
            return;
        }

        if (classLevelMetered != null) {
            meters.putIfAbsent(
                annotatedMethod.getMethod(),
                meterMetric(metrics, method, classLevelMetered)
            );

            return;
        }

        if (annotatedMethod.hasAnnotation()) {
            meters.putIfAbsent(
                annotatedMethod.getMethod(),
                meterMetric(
                    metrics,
                    method,
                    annotatedMethod.getAnnotation()
                )
            );
        }
    }

    private void registerExceptionMeteredAnnotations(final ResourceMethod method, final ExceptionMetered classLevelExceptionMetered) {
        final AnnotatedMethod<ExceptionMetered> annotatedMethod = AnnotatedMethod.get(method, ExceptionMetered.class);

        if (!annotatedMethod.hasMethod()) {
            return;
        }

        if (classLevelExceptionMetered != null) {
            exceptionMeters.putIfAbsent(
                annotatedMethod.getMethod(),
                new ExceptionMeterMetric(metrics, method, classLevelExceptionMetered));
            return;
        }

        if (annotatedMethod.hasAnnotation()) {
            exceptionMeters.putIfAbsent(
                annotatedMethod.getMethod(),
                new ExceptionMeterMetric(
                    metrics,
                    method,
                    annotatedMethod.getAnnotation()
                ));
        }
    }

    private void registerResponseMeteredAnnotations(final ResourceMethod method, final ResponseMetered classLevelResponseMetered) {
        final AnnotatedMethod<ResponseMetered> annotatedMethod = AnnotatedMethod.get(method, ResponseMetered.class);

        if (!annotatedMethod.hasMethod()) {
            return;
        }

        if (classLevelResponseMetered != null) {
            responseMeters.putIfAbsent(
                annotatedMethod.getMethod(),
                new ResponseMeterMetric(metrics, method, classLevelResponseMetered)
            );

            return;
        }

        if (annotatedMethod.hasAnnotation()) {
            responseMeters.putIfAbsent(
                annotatedMethod.getMethod(),
                new ResponseMeterMetric(
                    metrics,
                    method,
                    annotatedMethod.getAnnotation()
                )
            );
        }
    }

    private Timer timerMetric(final MetricRegistry registry,
                              final ResourceMethod method,
                              final Timed timed,
                              final String... suffixes) {
        final String name = chooseName(timed.name(), timed.absolute(), method, Timed.class, suffixes);
        return registry.timer(name, () -> new Timer(reservoirSupplier.get(), clock));
    }

    private Meter meterMetric(final MetricRegistry registry,
                              final ResourceMethod method,
                              final Metered metered) {
        final String name = chooseName(metered.name(), metered.absolute(), method, Metered.class);
        return registry.meter(name, () -> new Meter(clock));
    }

    protected static <A extends Annotation> String chooseName(final String explicitName, final boolean absolute, final ResourceMethod method,
                                       final Class<A> annotationClass, final String... suffixes) {
        final AnnotatedMethod annotatedMethod = AnnotatedMethod.get(method, annotationClass);
        assert annotatedMethod.hasAnnotation();

        final Method definitionMethod = annotatedMethod.getMethod();

        final String metricName;
        if (explicitName != null && !explicitName.isEmpty()) {
            metricName = absolute ? explicitName : name(definitionMethod.getDeclaringClass(), explicitName);
        } else {
            metricName = name(definitionMethod.getDeclaringClass(), definitionMethod.getName());
        }
        return name(metricName, suffixes);
    }

    private static class EventTypeAndMethod {

        private final RequestEvent.Type type;
        private final Method method;

        private EventTypeAndMethod(RequestEvent.Type type, Method method) {
            this.type = type;
            this.method = method;
        }

        static EventTypeAndMethod requestMethodStart(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.RESOURCE_METHOD_START, method);
        }

        static EventTypeAndMethod requestMatched(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.REQUEST_MATCHED, method);
        }

        static EventTypeAndMethod respFiltersStart(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.RESP_FILTERS_START, method);
        }

        static EventTypeAndMethod finished(Method method) {
            return new EventTypeAndMethod(RequestEvent.Type.FINISHED, method);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            EventTypeAndMethod that = (EventTypeAndMethod) o;

            if (type != that.type) {
                return false;
            }
            return method.equals(that.method);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + method.hashCode();
            return result;
        }
    }
}
