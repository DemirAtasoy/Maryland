package com.maryland;

import java.util.Iterator;

final class InheritanceIterator implements Iterator<Class<?>> {

    private Class<?> class_;
    public InheritanceIterator(final Object object) {
        this.class_ = object.getClass();
    }

    @Override
    public boolean hasNext() {
        return class_ != null;
    }

    @Override
    public Class<?> next() {
        final Class<?> class_ = this.class_;
        this.class_ = class_.getSuperclass();
        return class_;
    }
}
