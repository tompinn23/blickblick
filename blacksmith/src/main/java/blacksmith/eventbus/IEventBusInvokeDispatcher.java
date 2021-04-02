package blacksmith.eventbus;

public interface IEventBusInvokeDispatcher {
    void invoke(IEventListener listener, Event event);
}
