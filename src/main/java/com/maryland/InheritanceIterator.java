package com.maryland;

import java.util.Iterator;

/**
 *  An {@link Iterator} for iterating through a {@link Class}'s chain of inheritance
 */
final class InheritanceIterator implements Iterator<Class<?>> {

    private Class<?> class_;

    /**
     * Constructs a new {@link InheritanceIterator} starting at the specified {@link Class} and moving up the chain
     * of inheritance
     *
     * @param class_ The Class to begin iteration at
     */
    public InheritanceIterator(final Class<?> class_) {
        this.class_ = class_;
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
