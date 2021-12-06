package org.rx.crawler.dto;

import lombok.Getter;
import org.rx.bean.DataRange;

public enum NetLagLevel {
    A(0, 30),
    B(30, 50),
    C(50, 100),
    D(100, 150),
    E(150, 200);

    @Getter
    private final DataRange<Float> threshold = new DataRange<>();

    NetLagLevel(float start, float end) {
        threshold.start = start;
        threshold.end = end;
    }
}
