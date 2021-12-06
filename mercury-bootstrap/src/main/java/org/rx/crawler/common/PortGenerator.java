//package org.rx.crawler.common;
//
//import org.rx.bean.IdGenerator;
//
//import java.util.concurrent.atomic.AtomicInteger;
//
//public class PortGenerator {
//    private final int min, max;
//    private final AtomicInteger offset;
//
//    public PortGenerator(int min, int max) {
//        this.min = min;
//        this.max = max;
//        IdGenerator
//        offset = new AtomicInteger(min);
//    }
//
//    public int next() {
//        int port = offset.getAndIncrement();
//        if (port > max) {
//            offset.set(min);
//            return next();
//        }
//        return port;
//    }
//}
