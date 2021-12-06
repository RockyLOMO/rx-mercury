//package com.xiaoqing;
//
//import org.bytedeco.opencv.opencv_core.Mat;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import javax.imageio.ImageIO;
//import java.awt.*;
//import java.awt.image.BufferedImage;
//import java.io.File;
//import java.io.IOException;
//
//import static org.bytedeco.opencv.global.opencv_cudaarithm.flip;
//import static org.bytedeco.opencv.global.opencv_highgui.*;
//import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
//import static org.bytedeco.opencv.global.opencv_imgproc.*;
//
//public class ImgUtils {
//
//    private static Logger log = LoggerFactory.getLogger(ImgUtils.class);
//
//    public static void convert(String source, String result) {
//
//        File imageFile = new File(source);
//        try {
//            ImageProcessing(imageFile, "C:\\download\\tessdata_fast-4.1.0");
//        } catch (IOException e) {
//            log.warn("图像处理失败！");
//            e.printStackTrace();
//        }
//
//        String formatName = "png";
//        try {
//            File f = new File(source);
//            f.canRead();
//            BufferedImage src = ImageIO.read(f);
//            ImageIO.write(src, formatName, new File(result));
//        } catch (Exception e) {
//            log.warn("图像类型转换失败！");
//            e.printStackTrace();
//        }
//    }
//
//    public static void ImageProcessing(File sfile, String destDir) throws IOException {
//        File destF = new File(destDir);
//        if (!destF.exists()) {
//            destF.mkdirs();
//        }
//
//        BufferedImage bufferedImage = ImageIO.read(sfile);
//        int h = bufferedImage.getHeight();
//        int w = bufferedImage.getWidth();
//
//        // 灰度化
//        int[][] gray = new int[w][h];
//        for (int x = 0; x < w; x++) {
//            for (int y = 0; y < h; y++) {
//                int argb = bufferedImage.getRGB(x, y);
//                // 图像加亮（调整亮度识别率非常高）
//                int r = (int) (((argb >> 16) & 0xFF) * 1.1 + 30);
//                int g = (int) (((argb >> 8) & 0xFF) * 1.1 + 30);
//                int b = (int) (((argb >> 0) & 0xFF) * 1.1 + 30);
//                if (r >= 255) {
//                    r = 255;
//                }
//                if (g >= 255) {
//                    g = 255;
//                }
//                if (b >= 255) {
//                    b = 255;
//                }
//
//                //此处根据实际需要进行设定阈值
//                gray[x][y] = (int) Math.pow((
//                        Math.pow(r, 2.2) * 0.2973
//                                + Math.pow(g, 2.2) * 0.6274
//                                + Math.pow(b, 2.2) * 0.0753), 1 / 2.2);
//            }
//        }
//
//        // 二值化
//        int threshold = ostu(gray, w, h);
//        BufferedImage binaryBufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
//        for (int x = 0; x < w; x++) {
//            for (int y = 0; y < h; y++) {
//                if (gray[x][y] > threshold) {
//                    gray[x][y] |= 0x00FFFF;
//                } else {
//                    gray[x][y] &= 0xFF0000;
//                }
//                binaryBufferedImage.setRGB(x, y, gray[x][y]);
//            }
//        }
//
//        //去除干扰点 或 干扰线（运用八领域，即像素周围八个点判定，根据实际需要判定）
//        for (int y = 1; y < h - 1; y++) {
//            for (int x = 1; x < w - 1; x++) {
//
//                boolean lineFlag = false;//去除线判定
//                int pointflagNum = 0;//去除点判定
//
//                if (isBlack(binaryBufferedImage.getRGB(x, y))) {
//                    //左右像素点为"白"即空时，去掉此点
//                    if (isWhite(binaryBufferedImage.getRGB(x - 1, y)) && isWhite(binaryBufferedImage.getRGB(x + 1, y))) {
//                        lineFlag = true;
//                        pointflagNum += 2;
//                    }
//                    //上下像素点为"白"即空时，去掉此点
//                    if (isWhite(binaryBufferedImage.getRGB(x, y + 1)) && isWhite(binaryBufferedImage.getRGB(x, y - 1))) {
//                        lineFlag = true;
//                        pointflagNum += 2;
//                    }
//                    //斜上像素点为"白"即空时，去掉此点
//                    if (isWhite(binaryBufferedImage.getRGB(x - 1, y + 1)) && isWhite(binaryBufferedImage.getRGB(x + 1, y - 1))) {
//                        lineFlag = true;
//                        pointflagNum += 2;
//                    }
//                    if (isWhite(binaryBufferedImage.getRGB(x + 1, y + 1)) && isWhite(binaryBufferedImage.getRGB(x - 1, y - 1))) {
//                        lineFlag = true;
//                        pointflagNum += 2;
//                    }
//                    //去除干扰线
//                    if (lineFlag) {
////                        /binaryBufferedImage.setRGB(x, y, -1);
//                    }
//                    //去除干扰点
//                    if (pointflagNum > 3) {
//                        binaryBufferedImage.setRGB(x, y, -1);
//                    }
//                }
//            }
//        }
//
//
//        // 矩阵打印
//        for (int y = 0; y < h; y++) {
//            for (int x = 0; x < w; x++) {
//                if (isBlack(binaryBufferedImage.getRGB(x, y))) {
//                    System.out.print("*");
//                } else {
//                    System.out.print(" ");
//                }
//            }
//            System.out.println();
//        }
//
//        ImageIO.write(binaryBufferedImage, "jpg", new File(destDir, sfile
//                .getName()));
//    }
//
//    public static boolean isBlack(int colorInt) {
//        Color color = new Color(colorInt);
//        if (color.getRed() + color.getGreen() + color.getBlue() <= 300) {
//            return true;
//        }
//        return false;
//    }
//
//    public static boolean isWhite(int colorInt) {
//        Color color = new Color(colorInt);
//        if (color.getRed() + color.getGreen() + color.getBlue() > 300) {
//            return true;
//        }
//        return false;
//    }
//
//    public static int isBlackOrWhite(int colorInt) {
//        if (getColorBright(colorInt) < 30 || getColorBright(colorInt) > 730) {
//            return 1;
//        }
//        return 0;
//    }
//
//    public static int getColorBright(int colorInt) {
//        Color color = new Color(colorInt);
//        return color.getRed() + color.getGreen() + color.getBlue();
//    }
//
//    public static int ostu(int[][] gray, int w, int h) {
//        int[] histData = new int[w * h];
//        // Calculate histogram
//        for (int x = 0; x < w; x++) {
//            for (int y = 0; y < h; y++) {
//                int red = 0xFF & gray[x][y];
//                histData[red]++;
//            }
//        }
//
//        // Total number of pixels
//        int total = w * h;
//
//        float sum = 0;
//        for (int t = 0; t < 256; t++)
//            sum += t * histData[t];
//
//        float sumB = 0;
//        int wB = 0;
//        int wF = 0;
//
//        float varMax = 0;
//        int threshold = 0;
//
//        for (int t = 0; t < 256; t++) {
//            wB += histData[t]; // Weight Background
//            if (wB == 0)
//                continue;
//
//            wF = total - wB; // Weight Foreground
//            if (wF == 0)
//                break;
//
//            sumB += (float) (t * histData[t]);
//
//            float mB = sumB / wB; // Mean Background
//            float mF = (sum - sumB) / wF; // Mean Foreground
//
//            // Calculate Between Class Variance
//            float varBetween = (float) wB * (float) wF * (mB - mF) * (mB - mF);
//
//            // Check if new maximum found
//            if (varBetween > varMax) {
//                varMax = varBetween;
//                threshold = t;
//            }
//        }
//        return threshold;
//    }
//
//
//
////    public static void removeBackground(String imgUrl, String resUrl){
////        //定义一个临界阈值
////        int threshold = 300;
////        try{
////            BufferedImage img = ImageIO.read(new File(imgUrl));
////            int width = img.getWidth();
////            int height = img.getHeight();
////            for(int i = 1;i < width;i++){
////                for (int x = 0; x < width; x++){
////                    for (int y = 0; y < height; y++){
////                        Color color = new Color(img.getRGB(x, y));
////                        System.out.println("red:"+color.getRed()+" | green:"+color.getGreen()+" | blue:"+color.getBlue());
////                        int num = color.getRed()+color.getGreen()+color.getBlue();
////                        if(num >= threshold){
////                            img.setRGB(x, y, Color.WHITE.getRGB());
////                        }
////                    }
////                }
////            }
////            for(int i = 1;i<width;i++){
////                Color color1 = new Color(img.getRGB(i, 1));
////                int num1 = color1.getRed()+color1.getGreen()+color1.getBlue();
////                for (int x = 0; x < width; x++)
////                {
////                    for (int y = 0; y < height; y++)
////                    {
////                        Color color = new Color(img.getRGB(x, y));
////
////                        int num = color.getRed()+color.getGreen()+color.getBlue();
////                        if(num==num1){
////                            img.setRGB(x, y, Color.BLACK.getRGB());
////                        }else{
////                            img.setRGB(x, y, Color.WHITE.getRGB());
////                        }
////                    }
////                }
////            }
////            File file = new File(resUrl);
////            if (!file.exists())
////            {
////                File dir = file.getParentFile();
////                if (!dir.exists())
////                {
////                    dir.mkdirs();
////                }
////                try
////                {
////                    file.createNewFile();
////                }
////                catch (IOException e)
////                {
////                    e.printStackTrace();
////                }
////            }
////            ImageIO.write(img, "jpg", file);
////        }catch (Exception e){
////            e.printStackTrace();
////        }
////    }
////
////    public static void cuttingImg(String imgUrl){
////        try{
////            File newfile=new File(imgUrl);
////            BufferedImage bufferedimage=ImageIO.read(newfile);
////            int width = bufferedimage.getWidth();
////            int height = bufferedimage.getHeight();
////            if (width > 52) {
////                bufferedimage=ImgUtils.cropImage(bufferedimage,(int) ((width - 52) / 2),0,(int) (width - (width-52) / 2),(int) (height));
////                if (height > 16) {
////                    bufferedimage=ImgUtils.cropImage(bufferedimage,0,(int) ((height - 16) / 2),52,(int) (height - (height - 16) / 2));
////                }
////            }else{
////                if (height > 16) {
////                    bufferedimage=ImgUtils.cropImage(bufferedimage,0,(int) ((height - 16) / 2),(int) (width),(int) (height - (height - 16) / 2));
////                }
////            }
////            ImageIO.write(bufferedimage, "jpg", new File(imgUrl));
////        }catch (IOException e){
////            e.printStackTrace();
////        }
////    }
////
////
////    public static void main(String[] args) {
////        //图片地址
////        String imgUrl = "C:\\mysoftware\\images\\upload\\OcrImg\\20180607004153.png";
////        //得到灰度图像
////        getHuidu(imgUrl);
////        //得到二值化处理图像
////        getErzhihua(imgUrl);
////    }
////
////    //得到灰度图像
////    public static void getHuidu(String imgUrl){
////        Mat image=imread(imgUrl,CV_LOAD_IMAGE_GRAYSCALE);
////        //读入一个图像文件并转换为灰度图像（由无符号字节构成）
////        Mat image1=imread(imgUrl,CV_LOAD_IMAGE_COLOR);
////        //读取图像，并转换为三通道彩色图像，这里创建的图像中每个像素有3字节
////        //如果输入图像为灰度图像，这三个通道的值就是相同的
////        System.out.println("image has "+image1.channels()+" channel(s)");
////        //channels方法可用来检查图像的通道数
////        flip(image,image,1);//就地处理,参数1表示输入图像，参数2表示输出图像
////        //在一窗口显示结果
////        namedWindow("输入图片显示窗口");//定义窗口
////        imshow("输入图片显示窗口",image);//显示窗口
////        waitKey(0);//因为他是控制台窗口，会在mian函数结束时关闭;0表示永远的等待按键,正数表示等待指定的毫秒数
////    }
////
////    //得到二值化处理图像
////    public static void getErzhihua(String imgUrl){
////        // TODO Auto-generated method stub
////        Mat image=imread(imgUrl);	//加载图像
////        if(image.empty())
////        {
////            System.out.println("图像加载错误，请检查图片路径！");
////            return ;
////        }
////        imshow("原始图像",image);
////        Mat gray=new Mat();
////        cvtColor(image,gray,COLOR_RGB2GRAY);		//彩色图像转为灰度图像
////        imshow("灰度图像",gray);
////        Mat bin=new Mat();
////        threshold(gray,bin,120,255,THRESH_TOZERO); 	//图像二值化
////        imshow("二值图像",bin);
////        waitKey(0);
////    }
//}