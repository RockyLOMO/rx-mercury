package org.rx.crawler.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.annotation.Description;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum CountryType implements NEnum<CountryType> {
    @Description("中国")
    China(0),
    @Description("港澳台")
    HongKong(1),
    @Description("海外")
    Overseas(2);

    private final int value;
}
