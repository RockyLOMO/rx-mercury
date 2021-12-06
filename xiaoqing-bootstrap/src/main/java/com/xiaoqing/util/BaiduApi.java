package com.xiaoqing.util;

import com.baidu.aip.ocr.AipOcr;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.rx.io.IOStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public class BaiduApi {
    //设置APPID/AK/SK
    public static final String APP_ID = "24154389";
    public static final String API_KEY = "zFeWECfC5vQy152dcmVBxkiN";
    public static final String SECRET_KEY = "3vGW2L3NG9pdpqUsMc26X5T97UENIQHu";

    public List<String> ocr(IOStream<?, ?> stream) {
        // 初始化一个AipOcr
        AipOcr client = new AipOcr(APP_ID, API_KEY, SECRET_KEY);

        // 可选：设置网络连接参数
        client.setConnectionTimeoutInMillis(2000);
        client.setSocketTimeoutInMillis(60000);

        // 调用接口
        JSONObject res = client.basicGeneral(stream.toArray(), new HashMap<>());
        log.info("Ocr {} -> {}", stream.getName(), res.toString(2));
        JSONArray wordsResult = res.getJSONArray("words_result");
        List<String> words = new ArrayList<>(wordsResult.length());
        for (int i = 0; i < wordsResult.length(); i++) {
            words.add(wordsResult.getJSONObject(i).getString("words"));
        }
        return words;
    }
}
