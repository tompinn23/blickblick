package blacksmith.eventbus;

import net.jodah.typetools.TypeResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.ErrorManager;

import static blacksmith.eventbus.LogMarkers.EVENTBUS;

public class EventBus implements IEventExceptionHandler, IEventBus {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final boolean checkTypesOnDispatch = Boolean.parseBoolean(System.getProperty("eventbus.checkTypesOnDispatch", "false"));
    private static AtomicInteger maxID = new AtomicInteger(0);
    private final boolean trackPhases;
    private final Class<?> baseType;


    private ConcurrentHashMap<Object, List<IEventListener>> listeners = new ConcurrentHashMap<>();
    private final int busID = maxID.getAndIncrement();
    private final IEventExceptionHandler exceptionHandler;
    private volatile boolean shutdown = false;


    private EventBus() {
        ListenerList.resize(busID + 1);
        exceptionHandler = this;
        this.trackPhases = true;
        this.baseType = Event.class;
    }

    private EventBus(final IEventExceptionHandler handler, boolean trackPhase, boolean startShutdown, Class<?> baseType)
    {
        ListenerList.resize(busID + 1);
        if (handler == null) exceptionHandler = this;
        else exceptionHandler = handler;
        this.trackPhases = trackPhase;
        this.shutdown = startShutdown;
        this.baseType = baseType;
    }

    public EventBus(final BusBuilder busBuilder) {
        this(busBuilder.getExceptionHandler(), busBuilder.getTrackPhases(), busBuilder.isStartingShutdown(), busBuilder.getMarkerType());
    }



    public void register(final Object obj) {
        if(obj.getClass() == Class.class) {
            registerClass((Class<?>)obj);
        }
        else {
            registerObject(obj);
        }
    }

    private <T extends Event> Predicate<T> passCancelled(final boolean ignored) {
        return e-> ignored || !e.isCancelable() || !e.isCanceled();
    }

    private <T extends GenericEvent<? extends F>, F> Predicate<T> passGenericFilter(Class<F> type) {
        return e->e.getGenericType() == type;
    }

    private void checkNotGeneric(final Consumer<? extends Event> consumer) {
        checkNotGeneric(getEventClass(consumer));
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> Class<T> getEventClass(Consumer<T> consumer) {
        final Class<T> eventClass = (Class<T>) TypeResolver.resolveRawArgument(Consumer.class, consumer.getClass());
        if ((Class<?>)eventClass == TypeResolver.Unknown.class) {
            LOGGER.error(EVENTBUS, "Failed to resolve handler for \"{}\"", consumer.toString());
            throw new IllegalStateException("Failed to resolve consumer event type: " + consumer.toString());
        }
        return eventClass;
    }
    private void checkNotGeneric(final Class<? extends Event> eventType) {
        if (GenericEvent.class.isAssignableFrom(eventType)) {
            throw new IllegalArgumentException("Cannot register a generic event listener with addListener, use addGenericListener");
        }
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        checkNotGeneric(consumer);
        addListener(EventPriority.NORMAL, consumer);
    }


    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        checkNotGeneric(consumer);
        addListener(priority, false, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        checkNotGeneric(consumer);
        addListener(priority, passCancelled(receiveCancelled), consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        checkNotGeneric(eventType);
        addListener(priority, passCancelled(receiveCancelled), eventType, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, Consumer<T> consumer) {
        addGenericListener(genericClassFilter, EventPriority.NORMAL, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, Consumer<T> consumer) {
        addGenericListener(genericClassFilter, priority, false, consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        addListener(priority, passGenericFilter(genericClassFilter).and(passCancelled(receiveCancelled)), consumer);
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        addListener(priority, passGenericFilter(genericClassFilter).and(passCancelled(receiveCancelled)), eventType, consumer);
    }

    private <T extends Event> void addListener(final EventPriority priority, final Predicate<? super T> filter, final Consumer<T> consumer) {
        Class<T> eventClass = getEventClass(consumer);
        if (Objects.equals(eventClass, Event.class))
            LOGGER.warn(EVENTBUS,"Attempting to add a Lambda listener with computed generic type of Event. " +
                    "Are you sure this is what you meant? NOTE : there are complex lambda forms where " +
                    "the generic type information is erased and cannot be recovered at runtime.");
        addListener(priority, filter, eventClass, consumer);
    }

    private <T extends Event> void addListener(final EventPriority priority, final Predicate<? super T> filter, final Class<T> eventClass, final Consumer<T> consumer) {
        if (baseType != Event.class && !baseType.isAssignableFrom(eventClass)) {
            throw new IllegalArgumentException(
                    "Listener for event " + eventClass + " takes an argument that is not a subtype of the base type " + baseType);
        }
        addToListeners(consumer, eventClass, NamedEventListener.namedWrapper(e-> doCastFilter(filter, eventClass, consumer, e), consumer.getClass()::getName), priority);
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void doCastFilter(final Predicate<? super T> filter, final Class<T> eventClass, final Consumer<T> consumer, final Event e) {
        T cast = (T)e;
        if (filter.test(cast))
        {
            consumer.accept(cast);
        }
    }


    @Override
    public void unregister(Object object) {
        List<IEventListener> list = listeners.remove(object);
        if(list == null)
            return;
        for (IEventListener listener : list)
        {
            ListenerList.unregisterAll(busID, listener);
        }
    }

    @Override
    public boolean post(Event event) {         return post(event, (IEventListener::invoke));
    }

    @Override
    public boolean post(Event event, IEventBusInvokeDispatcher wrapper) {
        if (shutdown) return false;
        if (EventBus.checkTypesOnDispatch && !baseType.isInstance(event))
        {
            throw new IllegalArgumentException("Cannot post event of type " + event.getClass().getSimpleName() + " to this event. Must match type: " + baseType.getSimpleName());
        }

        IEventListener[] listeners = event.getListenerList().getListeners(busID);
        int index = 0;
        try
        {
            for (; index < listeners.length; index++)
            {
                if (!trackPhases && Objects.equals(listeners[index].getClass(), EventPriority.class)) continue;
                wrapper.invoke(listeners[index], event);
            }
        }
        catch (Throwable throwable)
        {
            exceptionHandler.handleException(this, event, listeners, index, throwable);
            throw throwable;
        }
        return event.isCancelable() && event.isCanceled();
    }

    @Override
    public void shutdown() {
        LOGGER.fatal(EVENTBUS, "EventBus {} shutting down - future events will not be posted.", busID, new Exception("stacktrace"));
        this.shutdown = true;
    }

    @Override
    public void start() {
        this.shutdown = false;
    }

    public void registerClass(Class<?> clazz) {
        Arrays.stream(clazz.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.isAnnotationPresent(SubscribeEvent.class))
                .forEach(method -> registerListener(clazz, method, method));

    }



    public void registerObject(Object obj) {

    }

    private void registerListener(final Object object, Method method, Method real) {
        Class<?>[] parameterTy = method.getParameterTypes();
        if(parameterTy.length != 1) {
            throw new IllegalArgumentException("Method:" + method + " is an event subscriber but has more than 1 argument");
        }

        Class<?> eventType = parameterTy[0];
        if(!Event.class.isAssignableFrom(eventType)) {
            throw new IllegalArgumentException("Method: " + method+ " is an event subscriber but takes an argument that is not an event subtype");
        }

        register(eventType, object, real);
    }

    private void register(Class<?> eventType, Object object, Method real) {
        try {
            final ASMEventHandler asm = new ASMEventHandler(object, real, IGenericEvent.class.isAssignableFrom(eventType));

            addToListeners(object, eventType, asm, asm.getPriority());
        } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
            LOGGER.error(EVENTBUS ,"Error registering event handler: {} {}", eventType, real, e);
        }
    }

    private void addToListeners(final Object target, final Class<?> eventType, final IEventListener listener, EventPriority priority) {
        ListenerList list = EventListenerHelper.getListenerList(eventType);
        list.register(busID, priority, listener);
        List<IEventListener> others = listeners.computeIfAbsent(target, k-> Collections.synchronizedList(new ArrayList<>()));
        others.add(listener);
    }



    @Override
    public void handleException(IEventBus bus, Event event, IEventListener[] listeners, int index, Throwable throwable) {
        LOGGER.error(EVENTBUS, ()->new EventBusErrorMessage(event, index, listeners, throwable));
    }
}
