package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class JdUnionBatchRequest implements Serializable {
    private static final long serialVersionUID = 1147866817282970012L;

    private String inputPath;
    private String outputPath;
    private List<JdUnionPromotionRequest> items = new ArrayList<JdUnionPromotionRequest>();
}
