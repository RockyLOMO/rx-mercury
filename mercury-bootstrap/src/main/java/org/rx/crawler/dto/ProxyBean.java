package org.rx.crawler.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.Objects;

@Getter
@Setter
@RequiredArgsConstructor
public class ProxyBean implements Serializable {
    private final ProxyType type;
    private final CountryType countryType;
    private final String endpoint;
    private double pingValue;
    private Date updateTime;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyBean proxyBean = (ProxyBean) o;
        return type == proxyBean.type &&
                Objects.equals(endpoint, proxyBean.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, endpoint);
    }
}
