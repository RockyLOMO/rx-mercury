package org.rx.crawler.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.annotation.Metadata;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum CountryType implements NEnum<CountryType> {
    @Metadata("中国")
    China(0),
    @Metadata("港澳台")
    HongKong(1),
    @Metadata("海外")
    Overseas(2);

    private final int value;
}
