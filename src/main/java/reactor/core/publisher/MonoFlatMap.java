/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import org.reactivestreams.*;

import reactor.core.Exceptions;

final class MonoFlatMap<T, R> extends Flux<R> {
    final Mono<? extends T> source;
    
    final Function<? super T, ? extends Publisher<? extends R>> mapper;
    
    public MonoFlatMap(Mono<? extends T> source, Function<? super T, ? extends Publisher<? extends R>> mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Subscriber<? super R> s) {
        if (FluxFlatMap.trySubscribeScalarMap(source, s, mapper, false)) {
            return;
        }
        source.subscribe(new FlattenSubscriber<>(s, mapper));
    }
    
    static final class FlattenSubscriber<T, R> implements Subscriber<T>, Subscription {
        final Subscriber<? super R> actual;
        
        final Function<? super T, ? extends Publisher<? extends R>> mapper;

        Subscription main;
        
        volatile Subscription inner;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<FlattenSubscriber, Subscription> INNER =
                AtomicReferenceFieldUpdater.newUpdater(FlattenSubscriber.class, Subscription.class, "inner");
        
        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<FlattenSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(FlattenSubscriber.class, "requested");
        
        boolean hasValue;
        
        public FlattenSubscriber(Subscriber<? super R> actual,
                Function<? super T, ? extends Publisher<? extends R>> mapper) {
            this.actual = actual;
            this.mapper = mapper;
        }

        @Override
        public void request(long n) {
            Subscription a = inner;
            if (a != null) {
                a.request(n);
            } else {
                if (Operators.validate(n)) {
                    Operators.getAndAddCap(REQUESTED, this, n);
                    a = inner;
                    if (a != null) {
                        n = REQUESTED.getAndSet(this, 0L);
                        if (n != 0L) {
                            a.request(n);
                        }
                    }
                }
            }
        }

        @Override
        public void cancel() {
            main.cancel();
            Operators.terminate(INNER, this);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.validate(this.main, s)) {
                this.main = s;
                
                actual.onSubscribe(this);
                
                s.request(Long.MAX_VALUE);
            }
        }
        
        boolean onSubscribeInner(Subscription s) {
            if (Operators.setOnce(INNER, this, s)) {
                
                long r = REQUESTED.getAndSet(this, 0L);
                if (r != 0) {
                    s.request(r);
                }
                
                return true;
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onNext(T t) {
            hasValue = true;
            
            Publisher<? extends R> p;
            
            try {
                p = mapper.apply(t);
            } catch (Throwable ex) {
                actual.onError(Exceptions.mapOperatorError(this, ex, t));
                return;
            }
            
            if (p == null) {
                actual.onError(Exceptions.mapOperatorError(this, new NullPointerException
                        ("The mapper returned" +
                        " a null " +
                        "Publisher."), t));
                return;
            }
            
            if (p instanceof Callable) {
                R v;
                
                try {
                    v = ((Callable<R>)p).call();
                } catch (Throwable ex) {
                    actual.onError(Exceptions.mapOperatorError(this, ex, t));
                    return;
                }
                
                if (v == null) {
                    actual.onComplete();
                } else {
                    onSubscribeInner(new Operators.ScalarSubscription<>(actual, v));
                }
                
                return;
            }
            
            p.subscribe(new InnerSubscriber<>(this, actual));
        }

        @Override
        public void onError(Throwable t) {
            if (hasValue) {
                Exceptions.onErrorDropped(t);
                return;
            }
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (!hasValue) {
                actual.onComplete();
            }
        }
        
        static final class InnerSubscriber<R> implements Subscriber<R> {

            final FlattenSubscriber<?, R> parent;
            
            final Subscriber<? super R> actual;
            
            public InnerSubscriber(FlattenSubscriber<?, R> parent, Subscriber<? super R> actual) {
                this.parent = parent;
                this.actual = actual;
            }

            @Override
            public void onSubscribe(Subscription s) {
                parent.onSubscribeInner(s);
            }

            @Override
            public void onNext(R t) {
                actual.onNext(t);
            }

            @Override
            public void onError(Throwable t) {
                actual.onError(t);
            }

            @Override
            public void onComplete() {
                actual.onComplete();
            }
            
        }
    }
}
