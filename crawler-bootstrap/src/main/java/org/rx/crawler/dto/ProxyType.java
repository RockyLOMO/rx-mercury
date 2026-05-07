package org.rx.crawler.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
@Getter
public enum ProxyType implements NEnum<ProxyType> {
    Socks4(1),
    Socks4a(2),
    Socks5(3),
    Shadowsocks(4);

    private final int value;
}
