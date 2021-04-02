package blacksmith.eventbus;

public interface IEventListener {
    void invoke(Event event);

    default String listenerName() {
        return getClass().getName();
    }
}
