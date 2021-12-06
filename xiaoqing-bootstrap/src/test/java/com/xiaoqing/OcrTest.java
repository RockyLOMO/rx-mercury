package com.xiaoqing;

import com.xiaoqing.util.BaiduApi;
import lombok.SneakyThrows;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.junit.jupiter.api.Test;
import org.rx.io.IOStream;

import java.io.File;

public class OcrTest {
    String img = "C:\\download\\1.jpg";

    @SneakyThrows
    @Test
    public void ocr() {
//        String img2 = "C:\\download\\2.jpg";
//        ImgUtils.convert(img, img2);
//        img = "C:\\download\\tessdata_fast-4.1.0\\1.jpg";
        ITesseract instance = new Tesseract();
        instance.setDatapath("C:\\Project\\tessdata_best");
        String result = instance.doOCR(new File(img));
        System.out.println(result);
    }

    @Test
    public void ocr2() {
        BaiduApi api = new BaiduApi();
        System.out.println(api.ocr(IOStream.wrap(img)));
    }
}
