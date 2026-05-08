package org.rx.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrowserWindowRect implements Serializable {
    private int x;
    private int y;
    private int width;
    private int height;
}
