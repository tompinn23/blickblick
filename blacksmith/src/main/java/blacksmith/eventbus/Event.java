package blacksmith.eventbus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

public class Event {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface HasResult{}

    public enum Result {
        DENY,
        DEFAULT,
        ALLOW
    }

    private boolean isCanceled = false;
    private Result result = Result.DEFAULT;
    private EventPriority phase = null;

    public Event() {
    }

    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    public void setCanceled(boolean canceled) {
        if(!isCancelable()) {
            throw new UnsupportedOperationException("Attempted Event#setCanceled() on a non cancelable event of type:" +
                    this.getClass().getCanonicalName());
        }
        isCanceled = canceled;
    }

    public boolean hasResult() {
        return false;
    }

    /**
     * Returns the value set as the result of this event
     */
    public Result getResult()
    {
        return result;
    }

    /**
     * Sets the result value for this event, not all events can have a result set, and any attempt to
     * set a result for a event that isn't expecting it will result in a IllegalArgumentException.
     *
     * The functionality of setting the result is defined on a per-event bases.
     *
     * @param value The new result
     */
    public void setResult(Result value)
    {
        result = value;
    }


    /**
     * Returns a ListenerList object that contains all listeners
     * that are registered to this event.
     *
     * Note: for better efficiency, this gets overridden automatically
     * using a Transformer, there is no need to override it yourself.
     * @see EventSubclassTransformer
     *
     * @return Listener List
     */
    public ListenerList getListenerList()
    {
        return EventListenerHelper.getListenerListInternal(this.getClass(), true);
    }

    @Deprecated //Unused by ASM generated code, kept for compatibility until we break version
    protected ListenerList getParentListenerList()
    {
        return EventListenerHelper.getListenerListInternal(this.getClass().getSuperclass(), false);
    }

    @Nullable
    public EventPriority getPhase()
    {
        return this.phase;
    }

    public void setPhase(@Nonnull EventPriority value)
    {
        Objects.requireNonNull(value, "setPhase argument must not be null");
        int prev = phase == null ? -1 : phase.ordinal();
        if (prev >= value.ordinal()) throw new IllegalArgumentException("Attempted to set event phase to "+ value +" when already "+ phase);
        phase = value;
    }
}
