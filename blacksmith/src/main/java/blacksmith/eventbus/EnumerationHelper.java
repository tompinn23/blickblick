package blacksmith.eventbus;

import java.util.Enumeration;

public class EnumerationHelper {

    public static <T> T firstElementOrNull(final Enumeration<T> enumeration) {
        return enumeration.hasMoreElements() ? enumeration.nextElement() : null;
    }
}
