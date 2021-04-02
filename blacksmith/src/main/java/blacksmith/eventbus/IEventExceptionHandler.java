package blacksmith.eventbus;

public interface IEventExceptionHandler {
    void handleException(IEventBus bus, Event event, IEventListener[] listeners, int index, Throwable throwable);
}
